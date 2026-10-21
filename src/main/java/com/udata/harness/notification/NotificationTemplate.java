package com.udata.harness.notification;

import org.noear.snack4.ONode;

import java.net.URI;
import java.net.URLEncoder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通知模板渲染：固定变量词表与安全插值。
 *
 * <p>模板中只允许 {@code ${name}} 形式的变量，且必须属于 {@link #VARIABLES}；
 * JSON 请求体先解析再插值，永远不作为代码求值。
 * URL 中的变量会做 URL 编码，但协议、域名、端口不允许使用变量，
 * 认证信息只能写入请求头——防止模板被用来向任意外部地址发起请求。</p>
 */
public final class NotificationTemplate {

    /** 模板可用的全部变量名（封闭词表，未知变量直接报错）。 */
    public static final List<String> VARIABLES = Collections.unmodifiableList(Arrays.asList(
        "workspace.id", "workspace.name", "session.id", "session.title", "execution.id", "execution.status",
        "execution.source", "execution.duration", "execution.durationMs", "result.summary", "result.error",
        "startedAt", "finishedAt"));

    /** {@code ${name}} 占位符。 */
    private static final Pattern TOKEN = Pattern.compile("\\$\\{([^{}]+)}");

    private NotificationTemplate() { }

    /**
     * 渲染文本模板：把 {@code ${name}} 替换为变量值。
     *
     * <p>未知变量或残留的 {@code ${} 直接报错；
     * {@code url} 为 true 时变量值做 URL 编码（空格编码为 {@code %20}）。</p>
     *
     * @param template 模板文本
     * @param variables 变量集
     * @param url 是否按 URL 场景编码
     * @return 渲染结果
     * @throws IllegalArgumentException 变量格式错误或包含未知变量
     */
    public static String render(String template, Map<String, Object> variables, boolean url) {
        // 1. 检查残留的非法 ${ 占位
        if (TOKEN.matcher(template).replaceAll("").contains("${")) throw new IllegalArgumentException("通知变量格式错误");
        // 2. 逐个替换变量：校验封闭词表，URL 场景对变量值做编码
        Matcher matcher = TOKEN.matcher(template);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String key = matcher.group(1);
            if (!VARIABLES.contains(key)) throw new IllegalArgumentException("未知通知变量：" + key);
            Object value = variables.get(key);
            String text = value == null ? "" : String.valueOf(value);
            if (url) {
                try {
                    text = URLEncoder.encode(text, "UTF-8").replace("+", "%20");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(text));
        }
        // 3. 追加尾部非占位文本
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * 递归展开 JSON 模板：对象键名不支持变量；字符串整体恰为单一变量时
     * 保留变量的原始类型（如数字、布尔），否则按字符串插值。
     *
     * @param node JSON 节点
     * @param variables 变量集
     * @return 展开后的 Java 值
     */
    private static Object expand(ONode node, Map<String, Object> variables) {
        if (node.isString()) {
            // 字符串：整体恰为单一变量时保留原始类型（数字、布尔），否则按文本插值
            String template = node.getString();
            Matcher matcher = TOKEN.matcher(template);
            if (matcher.matches() && VARIABLES.contains(matcher.group(1))) return variables.getOrDefault(matcher.group(1), "");
            return render(template, variables, false);
        }
        if (node.isArray()) {
            // 数组：逐项递归展开
            List<Object> result = new ArrayList<>();
            for (ONode item : node.getArray()) result.add(expand(item, variables));
            return result;
        }
        if (node.isObject()) {
            // 对象：键名不支持变量，值递归展开
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, ONode> item : node.getObject().entrySet()) {
                if (item.getKey().contains("${")) throw new IllegalArgumentException("JSON 字段名称不支持变量");
                result.put(item.getKey(), expand(item.getValue(), variables));
            }
            return result;
        }
        // 数字、布尔、null 等标量原样返回
        return node.getValue();
    }

    /**
     * 渲染请求体：{@code json} 格式先解析再插值并重新序列化，
     * 其余格式按纯文本渲染。
     *
     * @param template 请求体模板
     * @param format {@code json} 或 {@code text}
     * @param variables 变量集
     * @return 渲染后的请求体
     * @throws IllegalArgumentException 非法 JSON，或变量未写在字符串中
     */
    public static String body(String template, String format, Map<String, Object> variables) {
        if (!"json".equals(format)) return render(template, variables, false);
        try {
            return ONode.serialize(expand(ONode.ofJson(template), variables));
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("请求体须为合法 JSON，变量须写在字符串中", e);
        }
    }

    /**
     * 渲染并校验 URL：仅允许 http/https，禁止用户信息（{@code userinfo}）与片段，
     * 且协议、域名、端口不得包含变量（渲染前后必须一致）——防止模板绕过白名单。
     *
     * @param template URL 模板
     * @param variables 变量集
     * @return 渲染后的 URI
     * @throws IllegalArgumentException 协议/域名/端口不合法或含变量
     */
    public static URI url(String template, Map<String, Object> variables) {
        try {
            // 1. 将变量替换为占位词得到原始 URL，用于与渲染结果对比
            String original = TOKEN.matcher(template).replaceAll("variable");
            URI base = new URI(original), resolved = new URI(render(template, variables, true));
            // 2. 仅允许 HTTP/HTTPS，禁止 userinfo 与片段，且协议/域名/端口不得由变量改变
            if (!("http".equalsIgnoreCase(base.getScheme()) || "https".equalsIgnoreCase(base.getScheme()))
                || base.getHost() == null || base.getRawUserInfo() != null || base.getRawFragment() != null
                || !Objects.equals(base.getHost(), resolved.getHost()) || !Objects.equals(base.getScheme(), resolved.getScheme())
                || base.getPort() != resolved.getPort() || resolved.getHost() == null)
                throw new IllegalArgumentException();
            return resolved;
        } catch (Exception e) {
            throw new IllegalArgumentException("URL 须为 HTTP/HTTPS 地址；域名、端口不能使用变量，认证信息请放入请求头");
        }
    }

    /**
     * 渲染请求头值；渲染后禁止包含换行，防止请求头注入。
     *
     * @param value 请求头值模板
     * @param variables 变量集
     * @return 渲染后的值
     * @throws IllegalArgumentException 渲染结果含换行
     */
    public static String header(String value, Map<String, Object> variables) {
        String result = render(value, variables, false);
        if (result.contains("\r") || result.contains("\n")) throw new IllegalArgumentException("请求头不能包含换行");
        return result;
    }

    /**
     * 生成测试发送用的示例变量集：全部变量均有值，
     * 日期与执行 id 取当前时间/随机值，便于直接验证接收方链路。
     *
     * @return 示例变量集
     */
    public static Map<String, Object> sample() {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String name : VARIABLES) result.put(name, "");
        result.put("workspace.id", "test-workspace");
        result.put("workspace.name", "测试工作区");
        result.put("session.id", "test-session");
        result.put("session.title", "测试通知");
        result.put("execution.id", UUID.randomUUID().toString());
        result.put("execution.status", "succeeded");
        result.put("execution.source", "test");
        result.put("execution.duration", "1.2秒");
        result.put("execution.durationMs", 1200);
        result.put("result.summary", "这是一条 UdataHarness 测试通知，配置的 HTTP 接口已被调用。");
        result.put("startedAt", Instant.now().minusMillis(1200).toString());
        result.put("finishedAt", Instant.now().toString());
        return result;
    }
}
