package com.udata.harness.common.context;

import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.compress.CompressionUtil;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.snack4.ONode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 采集每次 Reason 调用的上下文遥测：请求侧足迹快照与响应侧用量观测。
 *
 * <p>在两个时点介入：</p>
 * <ul>
 * <li>{@link #onReasonStart} —— 观察上下文纪元（{@link ContextEpoch}），
 * 估算系统提示、工具定义与工作记忆的 token 足迹，暂存于 {@link #SNAPSHOT}，
 * 并对照上一次快照推算可复用的记忆前缀。</li>
 * <li>{@link #onReasonEnd} —— 按路由模型的价格归一化用量（{@link ContextUsage}），
 * 连同足迹写入会话观测日志；随后刷新或作废缓存决策基线
 * {@link #LAST_USAGE}/{@link #LAST_SNAPSHOT}。</li>
 * </ul>
 *
 * <p>不变量：足迹与基线只存放在 trace 局部的 extras 中，
 * 不跨会话/子代理共享；观测日志只含计数、哈希与标识，绝不写入消息正文。</p>
 */
public final class ContextTelemetryInterceptor implements ReActInterceptor {
    /** 上一次被采纳的归一化用量；纪元变更、错误响应或零输入时即被清除，由 {@link ContextEpoch#validUsage} 校验后使用。 */
    static final String LAST_USAGE = "udata.context.last-usage";
    /** 当前在途请求的足迹快照；{@link #onReasonEnd} 与改写成本门控（{@link ToolResultContextInterceptor}）读取。 */
    static final String SNAPSHOT = "udata.context.snapshot";
    /** 上一次被采纳的足迹快照，供下一次 {@link #onReasonStart} 估算共享记忆前缀；纪元变更时与 {@link #LAST_USAGE} 一并被清除。 */
    static final String LAST_SNAPSHOT = "udata.context.last-snapshot";
    private final ContextArtifactStore store;
    private final ContextCacheConfiguration config;

    /**
     * @param store   制品库：写入不含消息正文的会话观测日志
     * @param config  缓存配置：按路由模型提供成本策略与价格
     */
    public ContextTelemetryInterceptor(ContextArtifactStore store, ContextCacheConfiguration config) {
        this.store = store; this.config = config;
    }

    /**
     * Reason 发出前：观察上下文纪元并记录本次请求的上下文足迹。
     *
     * <p>工作记忆或 session 缺失时直接跳过、不留下快照。足迹视图包含
     * {@code estimatedTokens}、{@code categories}、{@code messageCount}、
     * {@code requestTimestamp}、{@code contextEpoch}，以及相对上一次快照的
     * {@code memoryPrefixEstimatedTokens}（固定前缀已变则为 0，
     * 无上一次快照则为 {@code null}）。</p>
     *
     * @param trace        当前 ReAct 轨迹
     * @param systemPrompt 本次请求的系统提示，只读不修改
     */
    @Override
    public void onReasonStart(ReActTrace trace, StringBuilder systemPrompt) {
        if (trace.getWorkingMemory() == null || trace.getSession() == null) return;
        String epoch = ContextEpoch.observe(trace, systemPrompt.toString());
        Footprint footprint = footprint(trace.getWorkingMemory().getMessages(), systemPrompt.toString(),
                trace.getOptions().getTools(), trace.getProtocolTools(), model(trace));
        Footprint previous = trace.getExtraAs(LAST_SNAPSHOT);
        footprint.view.put("memoryPrefixEstimatedTokens", footprint.shared(previous));
        footprint.view.put("requestTimestamp", System.currentTimeMillis());
        footprint.view.put("contextEpoch", epoch);
        trace.setExtra(SNAPSHOT, footprint);
    }

    /**
     * Reason 返回后：把足迹与归一化用量记录成一条 {@code scope=model_call} 的观测，
     * 并刷新缓存决策基线。
     *
     * <p>足迹缺失时观测仍会写入，只是没有分类明细。两条基线都要求本次请求的
     * 纪元仍是当前纪元；用量基线另需响应成功且输入侧 token 非零，
     * 足迹基线另需本次快照存在。任一条件不满足即从 trace extras 中移除，
     * 使过期或零值的用量不会成为后续缓存/改写决策的依据。</p>
     *
     * @param trace      当前 ReAct 轨迹，session 缺失时直接返回
     * @param response   模型响应，可为 {@code null}
     * @param message    助手消息，非空时补齐其稳定 ID 元数据（{@link ContextMessageIds}）
     * @param durationMs 本次 Reason 耗时（毫秒）
     */
    @Override
    public void onReasonEnd(ReActTrace trace, ChatResponse response, AssistantMessage message, long durationMs) {
        if (trace.getSession() == null) return;
        Footprint footprint = trace.getExtraAs(SNAPSHOT);
        Map<String, Object> event = footprint == null ? new LinkedHashMap<>() : new LinkedHashMap<>(footprint.view);
        event.put("id", trace.getRunId() + ":" + trace.getCurrentTurnId());
        event.put("timestamp", System.currentTimeMillis());
        event.put("runId", trace.getRunId()); event.put("reasonId", trace.getCurrentTurnId());
        event.put("agentName", trace.getAgentName()); event.put("model", model(trace));
        event.put("durationMs", durationMs);
        event.put("scope", "model_call");
        String requestEpoch = footprint == null ? ContextEpoch.current(trace) : (String) footprint.view.get("contextEpoch");
        event.put("contextEpoch", requestEpoch);
        if (message != null) ContextMessageIds.ensure(message);
        ContextCostPolicy policy = config.policy(model(trace));
        Map<String, Object> usage = ContextUsage.normalize(response == null ? null : response.getUsage(), policy.prices);
        usage.put("model", model(trace));
        usage.put("contextEpoch", requestEpoch);
        event.put("usage", usage);
        store.recordObservation(trace.getSession().getSessionId(), event);
        boolean currentRequest = java.util.Objects.equals(requestEpoch, ContextEpoch.current(trace));
        if (currentRequest
                && response != null && response.getError() == null && (response.getUsage() != null)
                && (response.getUsage().promptTokens() > 0 || response.getUsage().cacheReadInputTokens() > 0
                    || response.getUsage().cacheCreationInputTokens() > 0))
            trace.setExtra(LAST_USAGE, usage);
        else trace.getExtras().remove(LAST_USAGE);
        if (currentRequest && footprint != null) trace.setExtra(LAST_SNAPSHOT, footprint);
        else trace.getExtras().remove(LAST_SNAPSHOT);
    }

    /** 路由到的模型别名（未配置别名时为模型名），用作价格与成本策略的查找键。 */
    static String model(ReActTrace trace) { return trace.getOptions().getChatModel().getConfig().getNameOrModel(); }

    /**
     * 估算一次请求的上下文足迹：按 {@code system/tools/user/assistant/tool/references}
     * 分类累计 token，并逐条记录消息哈希与单条占用。
     *
     * <p>以 {@code <context-artifact} 开头的工具结果归入 {@code references}；
     * 助手消息另计 thinking 与工具调用，多模态消息另计内容块，
     * 每条消息附带 4 token 的固定帧开销。返回值的 {@code fixed} 指纹
     * 相同才认为系统提示与工具定义未变（见 {@link Footprint#shared}）。</p>
     *
     * @param messages      工作记忆中的消息
     * @param system        系统提示
     * @param tools         普通工具定义，可为 {@code null}
     * @param protocolTools 协议内建工具定义，可为 {@code null}
     * @param model         路由到的模型
     * @return 足迹快照；其 {@code view} 只含统计，不含消息正文
     */
    static Footprint footprint(List<ChatMessage> messages, String system, java.util.Collection<FunctionTool> tools,
                                java.util.Collection<FunctionTool> protocolTools, String model) {
        Map<String, Long> categories = new LinkedHashMap<>();
        for (String name : new String[]{"system", "tools", "user", "assistant", "tool", "references"}) categories.put(name, 0L);
        categories.put("system", (long) CompressionUtil.countTokens(system));
        StringBuilder definitions = new StringBuilder();
        for (java.util.Collection<FunctionTool> group : java.util.Arrays.asList(tools, protocolTools)) {
            if (group != null) for (FunctionTool tool : group) {
                definitions.append(tool.name()).append(tool.descriptionAndMeta()).append(tool.inputSchema()).append('\n');
            }
        }
        categories.put("tools", (long) CompressionUtil.countTokens(definitions.toString()));
        List<String> hashes = new ArrayList<>();
        List<Long> sizes = new ArrayList<>();
        for (ChatMessage message : messages) {
            String content = message.getContent() == null ? "" : message.getContent();
            String category = message instanceof ToolMessage ? content.startsWith("<context-artifact") ? "references" : "tool"
                    : message instanceof AssistantMessage ? "assistant" : "user";
            long tokens = CompressionUtil.countTokens(content) + 4;
            if (message instanceof AssistantMessage) {
                AssistantMessage assistant = (AssistantMessage) message;
                tokens += CompressionUtil.countTokens(assistant.getThinking() == null ? "" : assistant.getThinking());
                if (assistant.getToolCalls() != null) tokens += CompressionUtil.countTokens(ONode.serialize(assistant.getToolCalls()));
            }
            if (message instanceof ToolMessage && ((ToolMessage) message).isMultiModal())
                tokens += CompressionUtil.countTokens(ONode.serialize(((ToolMessage) message).getBlocks()));
            if (message instanceof UserMessage && ((UserMessage) message).isMultiModal())
                tokens += CompressionUtil.countTokens(ONode.serialize(((UserMessage) message).getBlocks()));
            if (message instanceof AssistantMessage && ((AssistantMessage) message).isMultiModal())
                tokens += CompressionUtil.countTokens(ONode.serialize(((AssistantMessage) message).getBlocks()));
            categories.put(category, categories.get(category) + tokens);
            hashes.add(CompactionCheckpointStore.messageHash(message)); sizes.add(tokens);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("estimatedTokens", categories.values().stream().mapToLong(Long::longValue).sum());
        view.put("categories", categories); view.put("messageCount", messages.size());
        return new Footprint(view, hashes, sizes, model + ":" + ContextArtifactStore.digest(system + definitions));
    }

    /**
     * 一次请求的上下文足迹：可序列化的统计视图，加逐消息哈希、单条占用与固定部分指纹。
     *
     * <p>{@link #view} 只含计数，不含消息正文；{@link #hashes} 与 {@link #sizes}
     * 按消息顺序一一对齐，用于估算与上一次请求共享的前缀。</p>
     */
    static final class Footprint {
        /** 统计视图：{@code estimatedTokens}、{@code categories}、{@code messageCount} 等，可直接并入观测事件。 */
        final Map<String, Object> view;
        /** 逐条消息哈希，顺序与 {@link #sizes} 一一对齐。 */
        final List<String> hashes;
        /** 逐条消息的 token 估算，与 {@link #hashes} 一一对齐。 */
        final List<Long> sizes;
        /** 固定部分指纹 {@code 模型:digest(系统提示+工具定义)}；变化即前缀不可复用。 */
        final String fixed;
        Footprint(Map<String, Object> view, List<String> hashes, List<Long> sizes, String fixed) {
            this.view = view; this.hashes = hashes; this.sizes = sizes; this.fixed = fixed;
        }
        /**
         * 估算与上一次足迹共享的记忆前缀 token。
         *
         * <p>固定指纹不同则返回 0；否则为固定部分（system + tools 类别）
         * 加上逐条哈希均相同的消息占用之和。</p>
         *
         * @param previous 上一次足迹；为 {@code null} 时返回 {@code null}（尚无基线）
         * @return 共享前缀的估算 token 数
         */
        Long shared(Footprint previous) {
            if (previous == null) return null;
            if (!fixed.equals(previous.fixed)) return 0L;
            Map<String, Long> categories = (Map<String, Long>) view.get("categories");
            long shared = categories.get("system") + categories.get("tools");
            for (int i = 0; i < Math.min(hashes.size(), previous.hashes.size()); i++) {
                if (!hashes.get(i).equals(previous.hashes.get(i))) break;
                shared += sizes.get(i);
            }
            return shared;
        }
    }
}
