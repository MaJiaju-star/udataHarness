package com.udata.harness.common.support;

import com.udata.harness.repository.ModelContextAgentSession;
import com.udata.harness.common.context.ContextPlanner;
import com.udata.harness.common.context.ContextProtection;
import com.udata.harness.common.context.ObservedSummaryModel;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.CompressionStrategy;
import org.noear.solon.ai.agent.react.intercept.ContextCompressionInterceptor;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.harness.agent.AgentDefinition;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * 持久化上下文压缩拦截器。
 *
 * <p>职责：在框架原生压缩能力之上，补齐两点差异：</p>
 * <ul>
 *   <li>把过期跨轮历史纳入压缩范围，由摘要承接；启用治理时保护最新用户请求原文；</li>
 *   <li>把主 Agent 的<b>实际模型视图</b>持久化到会话存储，保证进程重启后模型上下文可复原。</li>
 * </ul>
 *
 * <p>仅对主 Agent 会话（{@code AgentDefinition.AGENT_MAIN} + {@link ModelContextAgentSession}）
 * 生效，子 Agent 沿用框架默认行为。</p>
 */
public class PersistentContextCompressionInterceptor extends ContextCompressionInterceptor {
    /**
     * 工具调用组的归档去重标记，避免同一轮工具交换被重复归档。
     */
    private static final String ACTION_ID = "_udata_archived_action";

    /**
     * 本轮压缩是否真正产出了非空摘要；用于拒绝“摘要失败后的纯裁剪”落盘。
     */
    private static final String SUMMARY_SUCCEEDED = "_udata_summary_succeeded";
    private final CompressionStrategy strategy;
    private final int retries;
    private ContextPlanner planner;

    /**
     * 构造拦截器，并以包装方式接管摘要策略。
     *
     * @param maxMessages   触发压缩的消息数上限
     * @param ratio         压缩后目标占比
     * @param retries       摘要失败重试次数
     * @param triggerFactor 消息触发系数，用于提前或延后压缩时机
     * @param strategy      底层压缩策略，其摘要结果会被本类拦截以判定成败
     */
    public PersistentContextCompressionInterceptor(int maxMessages, double ratio, int retries,
                                                  double triggerFactor, CompressionStrategy strategy) {
        super(maxMessages, ratio, retries, (model, attempts, trace, messages) -> {
            // 1. 委托底层策略生成摘要
            ChatMessage summary;
            long started = System.currentTimeMillis();
            try {
                summary = strategy.compress(trace.getSession() instanceof ModelContextAgentSession
                        ? new ObservedSummaryModel(model, ((ModelContextAgentSession) trace.getSession()).contextMetrics(), trace.getRunId()) : model,
                        attempts, trace, messages);
            } finally {
                if (trace.getSession() instanceof ModelContextAgentSession) {
                    ((ModelContextAgentSession) trace.getSession()).contextMetrics().event("summary_attempt",
                            java.util.Collections.singletonMap("durationMs", System.currentTimeMillis() - started));
                }
            }
            // 2. 摘要非空则记下成功标记，供后续落盘校验
            if (summary != null && summary.getContent() != null && !summary.getContent().trim().isEmpty()) {
                ContextProtection.validateSummary(summary);
                trace.setExtra(SUMMARY_SUCCEEDED, true);
            }
            return summary;
        });
        this.strategy = strategy;
        this.retries = retries;
        setMessageTriggerFactor(triggerFactor);
        // 3. 不在摘要前直接头尾截断长消息；历史退出上下文必须有摘要承接
        setPerMessageCap(Integer.MAX_VALUE);
    }

    /** Opt-in here preserves compatibility for framework-only callers and existing sessions. */
    public PersistentContextCompressionInterceptor contextManagement(int toolResultMaxChars, int checkpointRetainMessages) {
        planner = new ContextPlanner(toolResultMaxChars, checkpointRetainMessages, strategy, retries);
        return this;
    }

    @Override
    public void onToolCallStart(ReActTrace trace, ToolExchanger call) {
        if (planner != null && isMainSession(trace)) planner.handle(trace, call);
    }

    /**
     * 推理开始前压缩上下文，并及时持久化压缩后的模型视图。
     *
     * @param trace        当前 ReAct 执行链路
     * @param systemPrompt 系统提示词构建器
     */
    @Override
    public void onReasonStart(ReActTrace trace, StringBuilder systemPrompt) {
        // 1. 非主会话交给框架默认处理
        if (!isMainSession(trace)) {
            super.onReasonStart(trace, systemPrompt);
            return;
        }
        if (planner != null) {
            planner.govern(trace);
            planner.ensureSnapshot(trace);
            planner.checkpoint(trace);
        }
        // 2. 进入压缩前记录基线，并解除首问的保护标记
        List<ChatMessage> before = prepareMemory(trace);
        // 3. 执行框架压缩，随后按“必须有摘要承接”的规则提交结果
        protectedCompression(trace, before, () -> super.onReasonStart(trace, systemPrompt));
        if (planner != null) ((ModelContextAgentSession) trace.getSession()).contextMetrics().request(trace.getWorkingMemory().getMessages());
    }

    /**
     * 推理重试时压缩上下文并持久化，逻辑与 {@link #onReasonStart} 保持一致。
     *
     * @param trace        当前 ReAct 执行链路
     * @param error        触发重试的异常
     * @param attempt      当前重试序号
     * @param systemPrompt 系统提示词
     * @return 本次调用是否改变了工作记忆（沿用父类语义）
     */
    @Override
    public boolean onReasonRetry(ReActTrace trace, Throwable error, int attempt, String systemPrompt) {
        if (!isMainSession(trace)) return super.onReasonRetry(trace, error, attempt, systemPrompt);
        // 1. 记录基线并解除首问保护标记
        List<ChatMessage> before = prepareMemory(trace);
        // 2. 执行重试压缩，再提交结果
        final boolean[] changed = {false};
        protectedCompression(trace, before, () -> changed[0] = super.onReasonRetry(trace, error, attempt, systemPrompt));
        return changed[0];
    }

    private void protectedCompression(ReActTrace trace, List<ChatMessage> before, Runnable operation) {
        ContextProtection protection = planner == null ? null : new ContextProtection(before);
        try {
            if (protection != null) trace.getWorkingMemory().replaceMessages(protection.prepare());
            operation.run();
            boolean compressed = trace.getWorkingMemory().getMessages().stream()
                    .anyMatch(m -> m.hasMetadata(META_COMPRESSED) && !before.contains(m));
            // A pure trim must be rejected before the no-compression order is restored.
            boolean removed = before.stream().anyMatch(m -> !trace.getWorkingMemory().getMessages().contains(m));
            if (removed && !compressed) throw new IllegalStateException("Compression removed history without a summary");
            if (protection != null) trace.getWorkingMemory().replaceMessages(
                    protection.finish(trace.getWorkingMemory().getMessages(), compressed));
            commitCompression(trace, before);
        } catch (RuntimeException e) {
            if (protection != null) protection.rollback();
            trace.getWorkingMemory().replaceMessages(before);
            throw e;
        }
    }

    /**
     * 为本次压缩准备记忆快照：解除首问保护标记，并重置摘要成功标记。
     *
     * <p>系统指令由框架单独构建，不属于会话历史；会话历史与用户消息允许参与摘要，
     * 因此不再整体占用保护段。</p>
     *
     * @param trace 当前 ReAct 执行链路
     * @return 压缩前的工作记忆副本，用于后续比对与回滚
     */
    private List<ChatMessage> prepareMemory(ReActTrace trace) {
        List<ChatMessage> messages = trace.getWorkingMemory().getMessages();
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            message.getMetadata().remove(AgentTrace.META_FIRST);
            if (planner != null && i == 0 && message.hasMetadata(ContextPlanner.TASK_SNAPSHOT)) {
                message.addMetadata(AgentTrace.META_FIRST, true);
            }
        }
        trace.setExtra(SUMMARY_SUCCEEDED, false);
        return new ArrayList<>(messages);
    }

    /**
     * 提交压缩结果：校验摘要有效性后落盘；若只是纯裁剪则回滚并报错。
     *
     * @param trace  当前 ReAct 执行链路
     * @param before 压缩前的工作记忆快照
     * @throws IllegalStateException 历史被移除但未产生摘要时抛出，原上下文保持不变
     */
    private void commitCompression(ReActTrace trace, List<ChatMessage> before) {
        // 1. 对比压缩前后，判断是否新生成了摘要、是否删除了历史
        List<ChatMessage> after = trace.getWorkingMemory().getMessages();
        boolean newSummary = after.stream().anyMatch(message -> message.hasMetadata(META_COMPRESSED)
                && !before.contains(message));
        boolean removed = before.stream().anyMatch(message -> !after.contains(message));
        // 2. 禁止把框架“摘要失败后的纯裁剪”提交成跨轮上下文；退回原快照并显式报错
        if (removed && (!newSummary || !Boolean.TRUE.equals(trace.getExtra(SUMMARY_SUCCEEDED)))) {
            trace.getWorkingMemory().replaceMessages(before);
            throw new IllegalStateException("Context compression did not produce a summary; original context retained");
        }
        if (planner != null && newSummary) {
            planner.refreshSnapshot(trace);
        }
        List<ChatMessage> committed = trace.getWorkingMemory().getMessages();
        // 3. 校验通过：持久化最新模型视图，并把新摘要计入代次
        try {
            ((ModelContextAgentSession) trace.getSession()).saveModelContext(committed, newSummary);
        } catch (RuntimeException e) {
            trace.getWorkingMemory().replaceMessages(before);
            throw e;
        }
        if (planner != null && newSummary) {
            java.util.Map<String, Object> event = new java.util.LinkedHashMap<>();
            event.put("beforeMessages", before.size());
            event.put("afterMessages", committed.size());
            ((ModelContextAgentSession) trace.getSession()).contextMetrics().event("budget_compression", event);
        }
    }

    /**
     * 单轮动作结束时，把完整的“推理 + 工具结果”归档进会话，并持久化模型视图。
     *
     * <p>Solon 4.1.0 默认只归档首问与终态；此处补存完整工具调用组，保证归档链路可追溯。</p>
     *
     * @param trace 当前 ReAct 执行链路
     * @param calls 本轮的模型工具交换集合
     */
    @Override
    public void onActionEnd(ReActTrace trace, Collection<ToolExchanger> calls) {
        if (!isMainSession(trace)) return;
        // 1. 定位本轮最后一条推理消息，并确认其携带工具调用
        AssistantMessage reason = trace.getLastReasonMessage();
        List<ChatMessage> memory = trace.getWorkingMemory().getMessages();
        int index = memory.lastIndexOf(reason);
        if (reason != null && reason.isToolCalls() && index >= 0) {
            String actionId = trace.getRunId() + ":" + trace.getCurrentReasonId();
            // 2. 以 actionId 去重，避免同一轮工具交换被重复归档
            boolean archived = trace.getSession().getMessages().stream()
                    .anyMatch(message -> actionId.equals(message.getMetadataAs(ACTION_ID)));
            if (!archived) {
                // 3. 收集紧随推理之后连续的工具结果消息，组成完整工具组
                List<ChatMessage> group = new ArrayList<>();
                group.add(reason);
                for (int i = index + 1; i < memory.size() && memory.get(i) instanceof ToolMessage; i++) {
                    group.add(memory.get(i));
                }
                // 4. 仅有推理而无工具结果时无需归档；否则打标并写入会话
                if (group.size() > 1) {
                    reason.addMetadata(ACTION_ID, actionId);
                    for (ChatMessage message : group) message.addMetadata(AgentTrace.META_RUN_ID, trace.getRunId());
                    trace.getSession().addMessage(group);
                }
            }
        }
        if (planner != null) planner.govern(trace);
        // 5. 归档完成后同步持久化最新模型视图
        save(trace);
    }

    /**
     * Agent 执行结束时持久化模型视图。
     *
     * @param trace 当前 ReAct 执行链路
     */
    @Override
    public void onAgentEnd(ReActTrace trace) {
        if (isMainSession(trace)) save(trace);
    }

    /**
     * 持久化当前工作记忆作为模型视图（非压缩场景，保持已有代次）。
     *
     * @param trace 当前 ReAct 执行链路
     */
    private void save(ReActTrace trace) {
        ((ModelContextAgentSession) trace.getSession()).saveModelContext(trace.getWorkingMemory().getMessages(), false);
    }

    /**
     * 判断是否为主 Agent 会话（仅该会话需要跨轮压缩与模型视图持久化）。
     *
     * @param trace 当前 ReAct 执行链路
     * @return 会话为 {@link ModelContextAgentSession} 且 Agent 名称为主 Agent 时返回 {@code true}
     */
    private boolean isMainSession(ReActTrace trace) {
        return trace.getSession() instanceof ModelContextAgentSession
                && AgentDefinition.AGENT_MAIN.equals(trace.getAgentName());
    }
}
