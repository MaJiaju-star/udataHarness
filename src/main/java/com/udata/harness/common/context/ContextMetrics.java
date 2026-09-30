package com.udata.harness.common.context;

import org.noear.snack4.ONode;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.io.BufferedReader;

/**
 * 会话级上下文指标：把本地决策与供应商回报的用量分开记录为 NDJSON 事件。
 *
 * <p>两类事实严格区分：{@code model_call}/{@code usage} 是供应商回报的用量，
 * {@code tool_projection}/{@code checkpoint}/{@code request} 等是本地决策。
 * {@code commonMessagePrefix} 只是本进程内相邻推理前模型消息的结构稳定程度，
 * <b>不是 API 缓存命中率</b>：它没有计入 system/tools 变化、缓存 TTL、分词与供应商内部请求转换。</p>
 *
 * <p>供应商的 prompt 字段是否包含缓存 Token 口径不同，因此不做跨供应商累加；
 * 用量取不到时保留为未知而非按零成本处理；{@code costMeasurementComplete} 恒为 {@code false}。</p>
 *
 * <p>指标写入失败只记警告，绝不影响正常推理与上下文提交；报告读取时单行解析失败会跳过该行，
 * 保证一行损坏不使整份报告不可用。</p>
 */
public class ContextMetrics {
    private static final Logger LOG = LoggerFactory.getLogger(ContextMetrics.class);
    /**
     * 固定 64 个写锁，按下标取模分配，减少不同会话写同一文件时的相互阻塞。
     */
    private static final Object[] WRITE_LOCKS = new Object[64];
    static { for (int i = 0; i < WRITE_LOCKS.length; i++) WRITE_LOCKS[i] = new Object(); }

    /**
     * 指标文件：会话上下文目录下的 {@code metrics.ndjson}。
     */
    private final Path path;

    /**
     * 上一次请求的消息指纹，仅用于计算结构稳定前缀；初始为 {@code null} 表示未知。
     */
    private List<String> previous;

    /**
     * 创建指标写入器。
     *
     * @param root 会话的 {@code <sessionId>.context/} 目录
     */
    public ContextMetrics(Path root) { path = root.resolve("metrics.ndjson"); }

    /**
     * 返回指标所在的会话上下文目录。
     *
     * <p>供请求级观测器反推指标位置使用。</p>
     *
     * @return {@code metrics.ndjson} 的父目录
     */
    public Path root() { return path.getParent(); }

    /**
     * 记录一次完整的模型调用（推理或摘要）。
     *
     * <p>{@code usageFinal} 仅在结果为 {@code success} 时为真：失败、未完成与被取消的调用即使
     * 带回了部分用量，也不应当作该次调用的最终计费依据。</p>
     *
     * @param id         调用唯一标识
     * @param purpose    调用用途，{@code reason} 或 {@code summary}
     * @param provider   供应商
     * @param model      实际模型名
     * @param runId      归属的运行标识，用于关联摘要与触发它的推理
     * @param durationMs 耗时毫秒
     * @param outcome    结果口径：{@code success}、{@code incomplete}、{@code failed} 或 {@code cancelled}
     * @param usage      供应商回报的用量，{@code null} 表示未知
     */
    public void modelCall(String id, String purpose, String provider, String model, String runId,
                          long durationMs, String outcome, AiUsage usage) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("callId", id); values.put("purpose", purpose); values.put("provider", provider);
        values.put("model", model); values.put("runId", runId); values.put("durationMs", durationMs);
        values.put("outcome", outcome); values.put("usageAvailable", usage != null);
        values.put("usageFinal", usage != null && "success".equals(outcome));
        if (usage != null) {
            values.put("promptTokens", usage.promptTokens()); values.put("completionTokens", usage.completionTokens());
            values.put("thinkTokens", usage.thinkTokens()); values.put("totalTokens", usage.totalTokens());
            values.put("cacheReadInputTokens", usage.cacheReadInputTokens());
            values.put("cacheCreationInputTokens", usage.cacheCreationInputTokens());
            values.put("cacheCreation5mInputTokens", usage.cacheCreation5mInputTokens());
            values.put("cacheCreation1hInputTokens", usage.cacheCreation1hInputTokens());
        }
        event("model_call", values);
    }

    /**
     * 以追加方式写入一条指标事件。
     *
     * <p>取锁后才建目录与追加，保证同一文件的并发追加不会交错；观测失败只记警告并返回，
     * 不允许因为指标问题中断一次成功的工具动作或丢弃已构建的上下文。</p>
     *
     * @param type    事件类型，如 {@code model_call}、{@code tool_projection}、{@code checkpoint}
     * @param details 事件字段
     */
    public synchronized void event(String type, Map<String, Object> details) {
        Map<String, Object> record = new LinkedHashMap<>(details);
        //1. 固定写入事件类型与时间，便于按类型统计与排序。
        record.put("type", type);
        record.put("time", System.currentTimeMillis());
        try {
            //2. 按文件路径哈希选锁：先建目录再追加，避免并发写互相交错。
            synchronized (WRITE_LOCKS[(path.toAbsolutePath().normalize().hashCode() & Integer.MAX_VALUE) % WRITE_LOCKS.length]) {
            Files.createDirectories(path.getParent());
            Files.write(path, (ONode.serialize(record) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            }
        } catch (java.io.IOException e) {
            //3. 可观测性不得中断主流程：写指标失败只告警，不影响推理与上下文。
            LOG.warn("Cannot write context metrics", e);
        }
    }

    /**
     * 记录压缩前的请求上下文体量，并计算与上一次请求的公共消息前缀长度。
     *
     * <p>计算前会剔除易变元数据（{@code first}/{@code META_FIRST}/{@code token_size}）再求指纹，
     * 避免这些标记把本可复用的前缀拆断；{@code cacheHitEstimate} 恒为 {@code false}，
     * 因为结构稳定不等于供应商缓存命中。重启后首次比较因 {@code previous} 为空而记 {@code null}。</p>
     *
     * @param messages 即将发给模型的消息列表
     */
    public synchronized void request(List<ChatMessage> messages) {
        //1. 逐条计算稳定指纹与字符数：先剔易变元数据，再序列化，避免标记干扰前缀比较。
        List<String> current = new ArrayList<>();
        long chars = 0;
        for (ChatMessage message : messages) {
            ChatMessage copy = ChatMessage.fromJson(ChatMessage.toJson(message));
            copy.getMetadata().remove("first");
            copy.getMetadata().remove(org.noear.solon.ai.agent.AgentTrace.META_FIRST);
            copy.getMetadata().remove("token_size");
            String json = ChatMessage.toJson(copy);
            current.add(ContextArtifactStore.digest(json));
            chars += json.length();
        }
        //2. 与上一次请求逐位比较，得到公共前缀长度；无可比基准时为未知。
        int prefix = 0;
        if (previous != null) {
            while (prefix < previous.size() && prefix < current.size()
                    && previous.get(prefix).equals(current.get(prefix))) prefix++;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("messages", messages.size());
        values.put("serializedChars", chars);
        values.put("commonMessagePrefix", previous == null ? null : prefix);
        values.put("cacheHitEstimate", false);
        event("request", values);
        //3. 更新基准，供下一次请求比较。
        previous = current;
    }

    /**
     * 记录一次成功的旧版推理用量。
     *
     * <p>保留兼容既有记录；新增用量应优先读 {@code providerUsageByModelAndPurpose} 分桶，
     * 因为这里只能反映成功推理，无法覆盖摘要、失败与取消。</p>
     *
     * @param model      模型名
     * @param durationMs 耗时毫秒
     * @param usage      供应商回报的用量，{@code null} 表示未知
     */
    public void usage(String model, long durationMs, AiUsage usage) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("model", model);
        values.put("durationMs", durationMs);
        values.put("usageAvailable", usage != null);
        if (usage != null) {
            values.put("promptTokens", usage.promptTokens());
            values.put("completionTokens", usage.completionTokens());
            values.put("cacheReadInputTokens", usage.cacheReadInputTokens());
            values.put("cacheCreationInputTokens", usage.cacheCreationInputTokens());
            values.put("cacheCreation5mInputTokens", usage.cacheCreation5mInputTokens());
            values.put("cacheCreation1hInputTokens", usage.cacheCreation1hInputTokens());
        }
        event("usage", values);
    }

    /**
     * 汇总指标文件，生成当前会话的观测报告。
     *
     * <p>单行解析失败会被跳过而非使整份报告失败；未知用量单独计入 {@code callsWithoutUsage}，
     * <b>不能按零费用处理</b>；{@code providerReportedReasonUsage} 仅代表旧版成功推理的记录，
     * 新增记录应读 {@code providerUsageByModelAndPurpose}。</p>
     *
     * @return 含事件计数、用量分桶、未知用量数与限制说明的报告；文件不存在时返回空报告
     * @throws IllegalStateException 指标文件无法读取时抛出
     */
    public synchronized Map<String, Object> report() {
        //1. 准备聚合容器：事件计数、旧版用量、按供应商/模型/用途分桶、未知用量与最近一次请求。
        Map<String, Object> report = new LinkedHashMap<>();
        Map<String, Long> counts = new LinkedHashMap<>();
        Map<String, Long> tokens = new LinkedHashMap<>();
        Map<String, Map<String, Long>> byModelAndPurpose = new LinkedHashMap<>();
        long unknownCalls = 0;
        Map<String, Object> lastRequest = null;
        if (Files.exists(path)) {
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                String line;
                //2. 逐行解析 NDJSON：损坏行跳过，保证单行问题不影响整份报告。
                while ((line = reader.readLine()) != null) {
                    ONode record;
                    try { record = ONode.ofJson(line); } catch (RuntimeException e) { continue; }
                    String type = record.get("type").getString();
                    counts.put(type, counts.getOrDefault(type, 0L) + 1);
                    if ("request".equals(type)) lastRequest = record.toBean(Map.class);
                    if ("model_call".equals(type)) {
                        //3. 调用按供应商/模型/用途分桶；用量未知的只计调用数，不参与 Token 累加。
                        String bucket = record.get("provider").getString() + "/" + record.get("model").getString() + "/" + record.get("purpose").getString();
                        Map<String, Long> totals = byModelAndPurpose.computeIfAbsent(bucket, key -> new LinkedHashMap<>());
                        totals.put("calls", totals.getOrDefault("calls", 0L) + 1);
                        if (!record.get("usageAvailable").getBoolean()) unknownCalls++;
                        else for (String key : Arrays.asList("promptTokens", "completionTokens", "thinkTokens", "totalTokens", "cacheReadInputTokens", "cacheCreationInputTokens", "cacheCreation5mInputTokens", "cacheCreation1hInputTokens"))
                            totals.put(key, totals.getOrDefault(key, 0L) + record.get(key).getLong());
                    }
                    //4. 旧版 usage 记录按原口径累加，保持与历史文件兼容；新口径不算缓存命中率。
                    if ("usage".equals(type) && record.get("usageAvailable").getBoolean()) {
                        for (String key : Arrays.asList("promptTokens", "completionTokens", "cacheReadInputTokens",
                                "cacheCreationInputTokens", "cacheCreation5mInputTokens", "cacheCreation1hInputTokens")) {
                            tokens.put(key, tokens.getOrDefault(key, 0L) + record.get(key).getLong());
                        }
                    }
                }
            } catch (java.io.IOException e) { throw new IllegalStateException("Cannot read context metrics", e); }
        }
        //5. 组装报告，并固定标注能力边界，避免下游把缺失用量当成零成本。
        report.put("events", counts);
        report.put("providerReportedReasonUsage", tokens);
        report.put("providerUsageByModelAndPurpose", byModelAndPurpose);
        report.put("callsWithoutUsage", unknownCalls);
        report.put("lastRequest", lastRequest);
        report.put("costMeasurementComplete", false);
        report.put("limitations", "model_call records cover observed reason and summary calls, including failure/cancellation. Legacy usage records cover successful reasoning only. Missing usage and provider-internal retries cannot be billed locally. Provider token conventions differ. No currency cost or cache hit rate is inferred.");
        return report;
    }
}
