package com.udata.harness.notification;

import com.udata.harness.common.domain.SessionMetadata;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Destroy;
import org.noear.solon.annotation.Inject;
import org.noear.solon.annotation.Init;
import org.noear.snack4.ONode;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static com.udata.harness.notification.NotificationExecution.text;
import static com.udata.harness.notification.NotificationRepository.rows;

/**
 * 通知发送服务：负责配置校验与持久化、入队以及后台 HTTP 投递。
 *
 * <p>发送模型为「先落库、再投递」：记录以 {@code queued} 状态落库后进入
 * 有界线程池异步发送，失败不自动重试；需要人工确认的场景由用户通过
 * {@link #resend} 手动重发。每位用户最多 20 条配置、200 条发送记录，
 * 清理旧记录时保留 {@code queued}/{@code sending} 状态的记录。</p>
 *
 * <p>会话联动：{@link #begin}、{@link #resume}、{@link #observe}、{@link #ended}
 * 分别在会话开始、分段恢复、事件流转、回合结束时维护 {@link NotificationExecution}
 * 状态；回合真正结束时按各配置声明的触发状态派发自动通知。</p>
 */
@Component
public class NotificationService implements AutoCloseable {

    /** 数据根目录（其下包含 users 子目录）。 */
    @Inject("${agent.data-dir:./data}")
    private String dataDir;

    /** 通知数据文件仓储。 */
    private NotificationRepository repository;

    /** 后台投递线程池：2 个守护线程、队列容量 64。 */
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
        new ArrayBlockingQueue<>(64), task -> {
            Thread thread = new Thread(task, "notification-http");
            thread.setDaemon(true);
            return thread;
        });

    /** 供手动构造使用；Solon 注入场景由 {@link #start()} 完成初始化。 */
    public NotificationService() { }

    /** 直接指定数据目录构造，并立即执行未完成记录的恢复。 */
    public NotificationService(Path root) {
        repository = new NotificationRepository(root);
        recover();
    }

    /** 容器启动回调：仓储尚未初始化时按注入的数据目录延迟初始化并恢复未完成记录。 */
    @Init
    public void start() {
        if (repository == null) {
            repository = new NotificationRepository(Paths.get(dataDir));
            recover();
        }
    }

    /** 关闭时立即中断后台投递线程池。 */
    @Override
    @Destroy
    public void close() {
        workers.shutdownNow();
    }

    /**
     * 服务重启恢复：将遗留的 {@code queued}/{@code sending} 记录标记为
     * {@code interrupted}——重启后发送结果无法确认，需人工检查接收方后决定是否重发。
     * 单个用户的数据读写失败仅告警，不影响其他用户。
     */
    private synchronized void recover() {
        for (String user : repository.users()) {
            try {
                // 1. 读取用户状态，找出遗留的排队/发送中记录
                Map<String, Object> state = repository.read(user);
                boolean changed = false;
                for (Map<String, Object> record : rows(state, "records")) {
                    // 2. 重启后发送结果无法确认，统一标记为 interrupted
                    if (Arrays.asList("queued", "sending").contains(record.get("status"))) {
                        record.put("status", "interrupted");
                        record.put("error", "服务重启，发送结果未确认；请检查接收方后决定是否手动重发");
                        changed = true;
                    }
                }
                // 3. 有变更才写回；单个用户失败仅告警
                if (changed) repository.write(user, state);
            } catch (RuntimeException e) {
                LoggerFactory.getLogger(getClass()).warn("Cannot recover notification records for {}", user);
            }
        }
    }

    /** 取字段文本并去除首尾空白；缺失时为空串。 */
    private static String value(Map<String, Object> source, String key) {
        return text(source, key).trim();
    }

    /**
     * 在 {@code configs} 或 {@code records} 中按 id 查找行。
     *
     * @param state 通知状态
     * @param kind 列表键名：{@code configs} 或 {@code records}
     * @param id 目标 id
     * @return 匹配的行
     * @throws IllegalArgumentException 不存在时抛出
     */
    private static Map<String, Object> find(Map<String, Object> state, String kind, String id) {
        return rows(state, kind).stream()
            .filter(row -> id.equals(row.get("id")))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("通知配置或发送记录不存在"));
    }

    /** 取配置的自定义请求头列表。 */
    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> headers(Map<String, Object> config) {
        return (List<Map<String, Object>>) config.get("headers");
    }

    /**
     * 生成对外展示的脱敏副本：URL 仅保留协议 + 主机 + 端口（{@code endpoint}），
     * 请求头值全部清空并以 {@code hasValue} 标记原值是否存在。
     *
     * @param config 原配置
     * @return 脱敏后的配置副本
     */
    private static Map<String, Object> publicConfig(Map<String, Object> config) {
        // 1. 复制原配置，剥离敏感的 URL 与请求头值
        Map<String, Object> result = new LinkedHashMap<>(config);
        URI url = NotificationTemplate.url(value(config, "url"), NotificationTemplate.sample());
        result.put("url", "");
        result.put("hasUrl", true);
        result.put("endpoint", url.getScheme() + "://" + url.getHost() + (url.getPort() < 0 ? "" : ":" + url.getPort()));
        // 2. 请求头值清空，仅保留名称与 hasValue 标记
        List<Map<String, Object>> output = new ArrayList<>();
        for (Map<String, Object> header : headers(config)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", header.get("name"));
            row.put("value", "");
            row.put("hasValue", !text(header, "value").isEmpty());
            output.add(row);
        }
        result.put("headers", output);
        return result;
    }

    /**
     * 查询当前用户的通知配置（已脱敏）、发送记录（不含变量，新记录在前）
     * 与可用模板变量列表。
     *
     * @param user 用户 id
     * @return 含 {@code configs}、{@code records}、{@code variables} 的映射
     */
    public synchronized Map<String, Object> list(String user) {
        // 1. 读取状态，配置逐条脱敏
        Map<String, Object> state = repository.read(user), output = new LinkedHashMap<>();
        List<Map<String, Object>> configs = new ArrayList<>(), records = new ArrayList<>();
        for (Map<String, Object> config : rows(state, "configs")) configs.add(publicConfig(config));
        // 2. 发送记录剔除模板变量副本，并倒序为新记录在前
        for (Map<String, Object> record : rows(state, "records")) {
            Map<String, Object> row = new LinkedHashMap<>(record);
            row.remove("variables");
            records.add(row);
        }
        Collections.reverse(records);
        // 3. 组装输出：配置、记录与可用变量词表
        output.put("configs", configs);
        output.put("records", records);
        output.put("variables", NotificationTemplate.VARIABLES);
        return output;
    }

    /**
     * 校验并规范化配置输入，生成可持久化的配置对象。
     *
     * <p>沿用原值规则：URL 为空且 {@code old} 非空时沿用原 URL；
     * 请求头值为空且未标记 {@code clearValue} 时沿用原值。
     * 校验项包括名称长度、URL 可解析且协议/域名/端口不含变量、请求方式、
     * 超时范围（1000–30000ms）、请求体格式与大小、请求头数量/命名/保留字段、
     * 触发状态非空以及成功判断路径与值的合法性。</p>
     *
     * @param input 用户提交的配置内容
     * @param old 更新时的原配置；新建时为 {@code null}
     * @return 可持久化的配置对象（新建时生成新 id）
     * @throws IllegalArgumentException 任一校验不通过
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> normalize(Map<String, Object> input, Map<String, Object> old) {
        // 1. 提取基础字段；URL 为空且提供了原配置时沿用原 URL
        Map<String, Object> result = new LinkedHashMap<>();
        String name = value(input, "name"), url = value(input, "url"), method = value(input, "method").toUpperCase(Locale.ROOT);
        if (url.isEmpty() && old != null) url = text(old, "url");
        // 2. 校验名称、URL 长度与可解析性（协议/域名/端口禁变量）
        if (name.isEmpty() || name.length() > 80) throw new IllegalArgumentException("通知名称须为 1–80 个字符");
        if (url.length() > 4000) throw new IllegalArgumentException("通知 URL 过长");
        NotificationTemplate.url(url, NotificationTemplate.sample());
        // 3. 校验请求方式与超时范围
        if (!Arrays.asList("GET", "POST", "PUT", "DELETE").contains(method)) throw new IllegalArgumentException("请求方式支持 GET、POST、PUT、DELETE");
        int timeout;
        try {
            timeout = Integer.parseInt(text(input, "timeoutMs"));
        } catch (Exception e) {
            throw new IllegalArgumentException("超时须为 1000–30000 毫秒");
        }
        if (timeout < 1000 || timeout > 30000) throw new IllegalArgumentException("超时须为 1000–30000 毫秒");
        // 4. 校验请求体格式、大小与模板合法性
        String format = value(input, "bodyFormat"), body = text(input, "body");
        if (!Arrays.asList("json", "text").contains(format) || body.length() > 65536) throw new IllegalArgumentException("请求体格式错误或超过 64K 字符");
        if (!body.isEmpty()) NotificationTemplate.body(body, format, NotificationTemplate.sample());

        // 5. 逐项校验并规范化请求头：数量、命名、保留字段、沿用原值、长度与模板
        List<Map<String, Object>> cleanHeaders = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Object rawHeaders = input.get("headers");
        if (!(rawHeaders instanceof List) || ((List<?>) rawHeaders).size() > 30) throw new IllegalArgumentException("请求头最多 30 项");
        for (Object raw : (List<?>) rawHeaders) {
            if (!(raw instanceof Map)) throw new IllegalArgumentException("请求头格式错误");
            Map<String, Object> header = (Map<String, Object>) raw;
            String key = value(header, "name"), keyLower = key.toLowerCase(Locale.ROOT), content = text(header, "value");
            if (!key.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]{1,80}") || !names.add(keyLower)
                    || Arrays.asList("host", "content-length", "transfer-encoding", "connection").contains(keyLower))
                throw new IllegalArgumentException("请求头名称无效、重复或属于自动计算字段");
            if (content.isEmpty() && old != null && !Boolean.TRUE.equals(header.get("clearValue"))) {
                for (Map<String, Object> previous : headers(old)) {
                    if (key.equalsIgnoreCase(text(previous, "name"))) content = text(previous, "value");
                }
            }
            if (content.length() > 8192) throw new IllegalArgumentException("请求头值过长");
            NotificationTemplate.header(content, NotificationTemplate.sample());
            Map<String, Object> clean = new LinkedHashMap<>();
            clean.put("name", key);
            clean.put("value", content);
            cleanHeaders.add(clean);
        }

        // 6. 过滤触发状态，仅保留合法值且至少一项
        Object rawStatuses = input.get("statuses");
        List<String> statuses = new ArrayList<>();
        if (!(rawStatuses instanceof List)) throw new IllegalArgumentException("请选择通知触发状态");
        for (Object status : (List<?>) rawStatuses) {
            if (Arrays.asList("succeeded", "failed", "cancelled").contains(status) && !statuses.contains(status)) statuses.add((String) status);
        }
        if (statuses.isEmpty()) throw new IllegalArgumentException("请选择至少一种通知触发状态");

        // 7. 校验成功判断字段路径与期望值
        String successPath = value(input, "successPath"), expected = value(input, "successValue");
        if (!successPath.isEmpty()) {
            if (!successPath.matches("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*){0,8}"))
                throw new IllegalArgumentException("成功判断字段须为简单 JSON 路径，如 code 或 data.success");
            if (!Arrays.asList("true", "false", "null").contains(expected) && !expected.matches("-?[0-9]+(?:\\.[0-9]+)?")
                    && !(expected.startsWith("\"") && expected.endsWith("\"")))
                throw new IllegalArgumentException("成功值请填写 JSON 标量，例如 0、true 或 \"ok\"");
            try {
                ONode.ofJson(expected);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("成功值不是合法 JSON");
            }
        }

        // 8. 组装可持久化配置；新建生成新 id，更新沿用原 id
        result.put("id", old == null ? UUID.randomUUID().toString() : old.get("id"));
        result.put("name", name);
        result.put("url", url);
        result.put("method", method);
        result.put("headers", cleanHeaders);
        result.put("body", body);
        result.put("bodyFormat", format);
        result.put("timeoutMs", timeout);
        result.put("enabled", Boolean.TRUE.equals(input.get("enabled")));
        result.put("statuses", statuses);
        result.put("successPath", successPath);
        result.put("successValue", expected);
        return result;
    }

    /**
     * 新建或更新配置：携带 {@code id} 更新，否则新建；每位用户最多 20 条。
     *
     * @param user 用户 id
     * @param input 配置内容
     * @return 保存后的配置 id
     * @throws IllegalArgumentException 校验不通过或超出数量上限
     */
    public synchronized String save(String user, Map<String, Object> input) {
        // 1. 携带 id 视为更新，先定位原配置；否则视为新建
        Map<String, Object> state = repository.read(user);
        String id = value(input, "id");
        Map<String, Object> old = id.isEmpty() ? null : find(state, "configs", id), config = normalize(input, old);
        // 2. 新建校验数量上限，更新时先移除旧条目
        if (old == null && rows(state, "configs").size() >= 20) throw new IllegalArgumentException("每位用户最多配置 20 条通知");
        if (old != null) rows(state, "configs").remove(old);
        // 3. 写入配置并落库，返回配置 id
        rows(state, "configs").add(config);
        repository.write(user, state);
        return text(config, "id");
    }

    /** 删除配置；已产生的发送记录保留。 */
    public synchronized void remove(String user, String id) {
        Map<String, Object> state = repository.read(user);
        rows(state, "configs").remove(find(state, "configs", id));
        repository.write(user, state);
    }

    /** 启用/停用配置；停用不取消已入队记录，自动发送前会复查启用状态。 */
    public synchronized void enabled(String user, String id, boolean enabled) {
        Map<String, Object> state = repository.read(user);
        find(state, "configs", id).put("enabled", enabled);
        repository.write(user, state);
    }

    /**
     * 以示例变量入队一次测试发送，配置无需先保存。
     *
     * @param user 用户 id
     * @param input 待测配置内容；携带已有 {@code id} 时沿用其保存的原值
     * @return 新发送记录 id
     */
    public synchronized String test(String user, Map<String, Object> input) {
        Map<String, Object> state = repository.read(user);
        String id = value(input, "id");
        return enqueue(user, normalize(input, id.isEmpty() ? null : find(state, "configs", id)), NotificationTemplate.sample(), "test");
    }

    /**
     * 手动重发历史记录：沿用原记录的配置与变量。
     *
     * @param user 用户 id
     * @param recordId 发送记录 id
     * @return 新发送记录 id
     * @throws IllegalArgumentException 记录仍处于 {@code queued}/{@code sending}，或不存在
     */
    public synchronized String resend(String user, String recordId) {
        // 1. 定位原记录，发送中不允许重复重发
        Map<String, Object> state = repository.read(user), record = find(state, "records", recordId);
        if (Arrays.asList("queued", "sending").contains(record.get("status"))) throw new IllegalArgumentException("该通知仍在发送中");
        // 2. 沿用原配置与模板变量重新入队
        return enqueue(user, find(state, "configs", text(record, "configId")), variables(record), "manual");
    }

    /** 取记录保存的模板变量副本。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> variables(Map<String, Object> record) {
        return (Map<String, Object>) record.get("variables");
    }

    /**
     * 按配置的触发状态派发自动通知：仅对启用且触发状态命中的配置入队。
     *
     * @param user 用户 id
     * @param variables 本次执行的模板变量（{@code execution.status} 决定命中）
     */
    public synchronized void dispatch(String user, Map<String, Object> variables) {
        // 1. 遍历配置，启用且触发状态命中的才入队
        for (Map<String, Object> config : new ArrayList<>(rows(repository.read(user), "configs"))) {
            if (Boolean.TRUE.equals(config.get("enabled")) && ((List<?>) config.get("statuses")).contains(variables.get("execution.status"))) {
                enqueue(user, config, variables, "automatic");
            }
        }
    }

    /**
     * 入队一条发送记录并提交后台投递。
     *
     * <p>自动发送按「配置 + 执行 id」去重，同一执行的重复入队返回既有记录 id；
     * 超过 200 条时优先清理非 {@code queued}/{@code sending} 的旧记录；
     * 线程池拒绝（队列满或服务已停止）时直接记为 {@code failed}。</p>
     *
     * @param user 用户 id
     * @param config 通知配置
     * @param variables 模板变量
     * @param kind 来源：{@code automatic}/{@code test}/{@code manual}
     * @return 发送记录 id
     */
    private synchronized String enqueue(String user, Map<String, Object> config, Map<String, Object> variables, String kind) {
        // 1. 自动发送按「配置 + 执行 id」去重，命中则返回既有记录
        Map<String, Object> state = repository.read(user);
        String executionId = text(variables, "execution.id");
        if ("automatic".equals(kind)) {
            for (Map<String, Object> old : rows(state, "records")) {
                if ("automatic".equals(old.get("kind")) && config.get("id").equals(old.get("configId")) && executionId.equals(old.get("executionId"))) {
                    return text(old, "id");
                }
            }
        }
        // 2. 组装新记录并以 queued 状态落库
        Map<String, Object> record = new LinkedHashMap<>();
        String id = UUID.randomUUID().toString();
        record.put("id", id);
        record.put("configId", config.get("id"));
        record.put("name", config.get("name"));
        record.put("kind", kind);
        record.put("executionId", executionId);
        record.put("sessionId", variables.get("session.id"));
        record.put("title", variables.get("session.title"));
        record.put("outcome", variables.get("execution.status"));
        record.put("status", "queued");
        record.put("createdAt", System.currentTimeMillis());
        record.put("variables", new LinkedHashMap<>(variables));
        record.put("attempts", 1);
        rows(state, "records").add(record);

        // 3. 超过上限时清理非 queued/sending 的旧记录
        Iterator<Map<String, Object>> iterator = rows(state, "records").iterator();
        while (rows(state, "records").size() > 200 && iterator.hasNext()) {
            if (!Arrays.asList("queued", "sending").contains(iterator.next().get("status"))) iterator.remove();
        }
        repository.write(user, state);

        // 4. 提交后台投递；线程池拒绝时直接记为失败
        try {
            workers.execute(() -> deliver(user, id, config, variables(record), "automatic".equals(kind)));
        } catch (RejectedExecutionException e) {
            finish(user, id, "failed", "通知发送队列已满或服务已停止，请稍后手动重发", 0);
        }
        return id;
    }

    /**
     * 后台投递一条记录：发送前复查配置仍启用（仅自动发送），置为 {@code sending}，
     * 完成后写入终态。超时、HTTP 非 2xx、成功判断不符、模板渲染失败等
     * 分别记录对应的失败原因。
     *
     * @param automatic 是否自动发送（需复查启用状态）
     */
    private void deliver(String user, String id, Map<String, Object> config, Map<String, Object> variables, boolean automatic) {
        try {
            // 1. 发送前复查配置仍启用（仅自动发送），并标记为 sending
            synchronized (this) {
                Map<String, Object> state = repository.read(user);
                if (automatic && rows(state, "configs").stream().noneMatch(row -> config.get("id").equals(row.get("id")) && Boolean.TRUE.equals(row.get("enabled")))) {
                    finish(user, id, "skipped", "通知已停用或删除", 0);
                    return;
                }
                find(state, "records", id).put("status", "sending");
                repository.write(user, state);
            }
            // 2. 执行 HTTP 请求，成功后写入终态
            int code = send(config, variables, id);
            finish(user, id, "succeeded", "", code);
        // 3. 按失败类型写入对应的终态原因
        } catch (HttpFailure e) {
            finish(user, id, "failed", e.getMessage(), e.code);
        } catch (SocketTimeoutException e) {
            finish(user, id, "failed", "HTTP 请求超时，接收方可能已收到，请确认后再重发", 0);
        } catch (Exception e) {
            finish(user, id, "failed", e instanceof IllegalArgumentException ? "模板渲染或请求参数无效，请检查配置" : "HTTP 请求失败，请检查地址、网络、认证与证书", 0);
        }
    }

    /**
     * 写入记录终态（状态、错误、HTTP 状态码、完成时间）；写失败仅告警。
     */
    private synchronized void finish(String user, String id, String status, String error, int code) {
        try {
            Map<String, Object> state = repository.read(user), record = find(state, "records", id);
            record.put("status", status);
            record.put("error", error);
            record.put("httpStatus", code);
            record.put("finishedAt", System.currentTimeMillis());
            repository.write(user, state);
        } catch (RuntimeException e) {
            LoggerFactory.getLogger(getClass()).warn("Cannot persist notification result {}", id);
        }
    }

    /** 携带 HTTP 状态码的请求失败。 */
    private static class HttpFailure extends IOException {
        final int code;

        HttpFailure(int code, String message) {
            super(message);
            this.code = code;
        }
    }

    /**
     * 执行一次 HTTP 请求：渲染 URL/请求头/请求体后发送，不跟随重定向、禁用缓存，
     * 请求头带 {@code X-Udata-Notification-Id}；配置了成功判断时读取响应体
     * （上限 16KB）校验指定路径的值。
     *
     * @return HTTP 状态码（2xx）
     * @throws HttpFailure 非 2xx、响应超限/读取超时、成功判断不符或响应非 JSON
     */
    private int send(Map<String, Object> config, Map<String, Object> variables, String id) throws IOException {
        URI uri = NotificationTemplate.url(text(config, "url"), variables);
        HttpURLConnection connection = (HttpURLConnection) uri.toURL().openConnection();
        try {
            // 1. 配置超时与请求行为：禁跟随重定向、禁缓存
            int timeout = ((Number) config.get("timeoutMs")).intValue();
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            connection.setInstanceFollowRedirects(false);
            connection.setUseCaches(false);

            // 2. 设置请求方法与通知 id 请求头
            String method = text(config, "method");
            connection.setRequestMethod(method);
            connection.setRequestProperty("X-Udata-Notification-Id", id);

            // 3. 渲染并设置自定义请求头，记录是否已带 Content-Type
            boolean contentType = false;
            for (Map<String, Object> header : headers(config)) {
                String name = text(header, "name");
                if ("Content-Type".equalsIgnoreCase(name)) contentType = true;
                connection.setRequestProperty(name, NotificationTemplate.header(text(header, "value"), variables));
            }

            // 4. 非 GET 且有请求体时渲染写出，缺省补 Content-Type
            if (!"GET".equals(method) && !text(config, "body").isEmpty()) {
                if (!contentType) {
                    connection.setRequestProperty("Content-Type", "json".equals(config.get("bodyFormat"))
                        ? "application/json; charset=utf-8" : "text/plain; charset=utf-8");
                }
                byte[] body = NotificationTemplate.body(text(config, "body"), text(config, "bodyFormat"), variables).getBytes(StandardCharsets.UTF_8);
                connection.setDoOutput(true);
                connection.setFixedLengthStreamingMode(body.length);
                try (OutputStream output = connection.getOutputStream()) {
                    output.write(body);
                }
            }

            // 5. 读取状态码，非 2xx 视为失败
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new HttpFailure(code, "HTTP 返回 " + code + "（重定向不会自动跟随）");

            // 6. 配置了成功判断时读取响应体并校验指定路径的值
            if (!text(config, "successPath").isEmpty()) {
                // 6.1 读取响应体，超 16KB 或超时即失败
                ByteArrayOutputStream buffer = new ByteArrayOutputStream();
                long deadline = System.currentTimeMillis() + timeout;
                try (InputStream input = connection.getInputStream()) {
                    byte[] bytes = new byte[2048];
                    int count;
                    while ((count = input.read(bytes)) != -1) {
                        buffer.write(bytes, 0, count);
                        if (buffer.size() > 16384 || System.currentTimeMillis() > deadline) {
                            throw new HttpFailure(code, "成功判断响应超过 16KB 或读取超时");
                        }
                    }
                }
                // 6.2 解析 JSON 沿路径取值，与期望值比对
                try {
                    ONode node = ONode.ofJson(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
                    for (String part : text(config, "successPath").split("\\.")) {
                        if (!node.hasKey(part)) throw new HttpFailure(code, "响应缺少配置的成功判断字段");
                        node = node.get(part);
                    }
                    if (!node.toJson().equals(ONode.ofJson(text(config, "successValue")).toJson())) {
                        throw new HttpFailure(code, "HTTP 成功，但响应字段不符合配置的成功条件");
                    }
                } catch (RuntimeException e) {
                    throw new HttpFailure(code, "响应不是可判断的 JSON");
                }
            }
            return code;
        } finally {
            connection.disconnect();
        }
    }

    /**
     * 会话开始：创建执行状态放入会话上下文并刷新快照。
     *
     * @param session 代理会话
     * @param metadata 会话元数据（工作区、会话 id、标题）
     * @param workspaceName 工作区名称
     * @param scheduledId 计划任务 id；为空表示聊天执行，执行 id 使用随机 UUID
     */
    public void begin(AgentSession session, SessionMetadata metadata, String workspaceName, String scheduledId) {
        // 1. 计划任务用自身 id，聊天执行用随机 UUID
        boolean scheduled = scheduledId != null && !scheduledId.isEmpty();
        // 2. 创建执行状态放入会话上下文并刷新快照
        session.getContext().put(NotificationExecution.KEY, NotificationExecution.begin(
            scheduled ? scheduledId : UUID.randomUUID().toString(),
            scheduled ? "scheduled" : "chat",
            metadata.getWorkspaceId(), workspaceName, metadata.getSessionId(), metadata.getTitle(), System.currentTimeMillis()));
        session.updateSnapshot();
    }

    /** 取会话上下文中的执行状态；不存在时返回 {@code null}。 */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> execution(AgentSession session) {
        Object state = session.getContext().getAs(NotificationExecution.KEY);
        return state instanceof Map ? (Map<String, Object>) state : null;
    }

    /** 分段恢复：重置计时起点；无执行状态时忽略。 */
    public void resume(AgentSession session) {
        Map<String, Object> state = execution(session);
        if (state != null) NotificationExecution.resume(state, System.currentTimeMillis());
    }

    /** 转发流式事件给执行状态机；无执行状态时忽略。 */
    public void observe(AgentSession session, Map<String, Object> event) {
        Map<String, Object> state = execution(session);
        if (state != null) NotificationExecution.observe(state, event);
    }

    /**
     * 回合结束：生成模板变量并派发自动通知，标记执行已结束并刷新快照。
     * 分段结束（回合未完成）时仅累计耗时；失败仅告警，不影响会话主流程。
     *
     * @param user 用户 id
     * @param signal 结束信号
     * @param stopped 是否被用户主动停止
     * @param pending 是否只是分段结束、回合仍在继续
     */
    public void ended(String user, AgentSession session, String signal, boolean stopped, boolean pending) {
        try {
            // 1. 无执行状态时直接忽略
            Map<String, Object> state = execution(session);
            if (state == null) return;
            // 2. 生成模板变量；分段结束时仅累计耗时
            Map<String, Object> variables = NotificationExecution.end(state, signal, stopped, pending, System.currentTimeMillis());
            if (variables != null) {
                // 3. 按触发状态派发自动通知，并标记执行已结束
                dispatch(user, variables);
                state.put("finished", true);
            }
            session.updateSnapshot();
        } catch (RuntimeException e) {
            LoggerFactory.getLogger(getClass()).warn("Cannot enqueue conversation notification for {}", session.getSessionId());
        }
    }
}
