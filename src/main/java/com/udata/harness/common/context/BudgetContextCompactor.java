package com.udata.harness.common.context;

import com.udata.harness.repository.ModelContextAgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.*;
import org.noear.solon.ai.chat.message.*;
import java.util.*;

/**
 * 主会话的预算式上下文压缩器。
 *
 * <p>触发判断、尾部保留与最终提交共用同一个 {@link TokenPressureMeter} 计量结果，
 * 不做降级裁剪；摘要生成期间不改动活动记忆，提交成功后才整体替换。</p>
 */
public final class BudgetContextCompactor {
    private final CompressionStrategy strategy;
    private final int maxMessages, retries;
    private final double ratio, factor;
    /**
     * @param strategy    摘要生成策略
     * @param maxMessages 保留消息条数上限，并作为大量短消息的辅助守卫基数
     * @param retries     摘要模型的重试次数
     * @param ratio       上下文压力阈值比例，用于换算目标预算
     * @param factor      消息守卫倍数，未超限时按 {@code maxMessages * factor} 判断
     */
    public BudgetContextCompactor(CompressionStrategy strategy, int maxMessages, int retries, double ratio, double factor) {
        this.strategy = strategy; this.maxMessages = maxMessages; this.retries = retries; this.ratio = ratio; this.factor = factor;
    }
    /**
     * 按预算对主会话历史执行一次压缩，必要时直接提交摘要结果。
     *
     * @param trace    当前 ReAct 运行轨迹
     * @param system   参与计量的 system 提示词
     * @param force    是否忽略软阈值强制压缩
     * @param attempt  已压缩次数，非强制时用于收紧重试目标
     * @param planner  任务快照规划器
     * @return {@code true} 表示本次已提交新摘要，{@code false} 表示未达到压缩条件
     * @throws IllegalStateException 摘要无法收敛到目标预算时抛出
     */
    public boolean compact(ReActTrace trace, String system, boolean force, int attempt, ContextPlanner planner) {
        ModelContextAgentSession session = (ModelContextAgentSession) trace.getSession();
        List<ChatMessage> before = new ArrayList<>(trace.getWorkingMemory().getMessages());
        //1. 先测一次整体压力，未超阈值且消息条数未超守卫倍数时直接结束
        TokenPressureMeter.Measurement initial = session.tokenPressure().measure(trace, system, before, ratio);
        session.contextMetrics().event("context_pressure", initial.detail());
        if (!force && initial.inputTokens <= initial.threshold && before.size() <= Math.ceil(maxMessages * factor)) {
            publish(trace, initial, initial, before.size(), before.size(), false);
            bind(trace, initial); return false;
        }
        //2. 定位当前用户原文，它在压缩中被整体保护
        SummaryCommitGuard guard = new SummaryCommitGuard(trace);
        ChatMessage user = null;
        for (ChatMessage message : before) {
            if (message instanceof UserMessage && !message.hasMetadata(ContextPlanner.TASK_SNAPSHOT)
                    && !message.hasMetadata(ContextCompressionInterceptor.META_COMPRESSED)) user = message;
        }
        //3. 按 Token 预算从尾部保留最近消息，并受消息条数守卫约束
        // Retain a token-priced tail, bounded also by the configured message guard.
        long tailBudget = Math.max(1, initial.threshold / (force ? 8 : 4));
        int cut = before.size(); long tail = 0; int retained = 0;
        while (cut > 0 && retained < Math.min(maxMessages, 20)) {
            long cost = TokenPressureMeter.messagesTokens(Collections.singletonList(before.get(cut - 1)));
            if (retained >= 3 && tail + cost > tailBudget) break;
            cut--; retained++; tail += cost;
        }
        while (cut > 0 && cut < before.size() && before.get(cut) instanceof ToolMessage) cut--;
        //4. 压缩目标：强制压缩按尝试次数收紧，非强制只压到阈值的 4/5
        long target = force ? Math.max(1, initial.threshold / Math.max(2, attempt + 1)) : Math.max(1, initial.threshold * 4 / 5);
        RuntimeException failure = null;
        //5. 最多三轮：首轮失败后扩大可摘要范围，仍不达标则抛出最后的失败原因
        for (int pass = 0; pass < 3; pass++) {
            List<ChatMessage> old = new ArrayList<>();
            for (int i = 0; i < cut; i++) {
                ChatMessage message = before.get(i);
                if (message != user && !message.hasMetadata(ContextPlanner.TASK_SNAPSHOT)) old.add(message);
            }
            if (old.isEmpty()) throw new IllegalStateException("Protected context and current tool group exceed budget; no history can be summarized");
            long started = System.currentTimeMillis();
            try {
                ChatMessage summary = strategy.compress(new ObservedSummaryModel(trace.getOptions().getChatModel(),
                        session.contextMetrics(), trace.getRunId()), retries, trace, old);
                //6. 校验修订未被并发改动，并确认摘要确实缩小了内容
                guard.check(); SummaryCommitGuard.shrinking(old, summary);
                summary.addMetadata(ContextCompressionInterceptor.META_COMPRESSED, true);
                List<ChatMessage> after = new ArrayList<>();
                for (ChatMessage message : before) {
                    if (message.hasMetadata(ContextPlanner.TASK_SNAPSHOT)) after.add(message);
                }
                after.add(summary);
                if (user != null && before.indexOf(user) < cut) after.add(user);
                after.addAll(before.subList(cut, before.size()));
                //7. 组装候选上下文：任务快照 + 摘要 + 用户原文 + 保留尾部，并校验工具调用组完整
                ContextProtection.validateToolGroups(before, after);
                TokenPressureMeter.Measurement next = session.tokenPressure().measure(trace, system, after, ratio);
                if (next.inputTokens >= initial.inputTokens) throw new IllegalStateException("Summary does not reduce complete request pressure");
                if (next.inputTokens > target) {
                    failure = new IllegalStateException("Summary plus protected context exceeds target budget");
                    cut = before.size(); continue; // Expand once to all eligible history; never silently trim.
                }
                //8. 只在候选上下文上刷新任务快照，写入成功前不动活动记忆
                // Refresh the snapshot on a candidate only; keep live memory untouched until the write succeeds.
                after = planner.refreshedSnapshot(trace, after);
                next = session.tokenPressure().measure(trace, system, after, ratio);
                if (next.inputTokens > target || next.inputTokens >= initial.inputTokens)
                    throw new IllegalStateException("Refreshed task snapshot exceeds summary budget");
                //9. 复核修订号后落盘，再整体替换活动记忆
                guard.check();
                session.saveModelContextChecked(after, true, guard.revision(), guard.archiveSize());
                trace.getWorkingMemory().replaceMessages(after);
                session.contextMetrics().event("budget_compression", next.detail());
                publish(trace, initial, next, before.size(), after.size(), true);
                bind(trace, next); return true;
            } catch (RuntimeException e) {
                session.contextMetrics().event("summary_rejected", Collections.singletonMap("reason", e.getMessage()));
                throw e;
            } finally {
                session.contextMetrics().event("summary_attempt", Collections.singletonMap("durationMs", System.currentTimeMillis() - started));
            }
        }
        throw failure;
    }
    /**
     * 把本次计量使用的模型窗口绑定到工具上下文，供后续工具复用。
     *
     * @param trace       当前 ReAct 运行轨迹
     * @param measurement 本次计量结果
     */
    public static void bind(ReActTrace trace, TokenPressureMeter.Measurement measurement) {
        trace.getOptions().getToolContext().put(TokenPressureMeter.FRAME, measurement.frame);
    }
    /**
     * 向流式通道推送上下文体积事件，无流式 sink 时静默跳过。
     *
     * @param trace      当前 ReAct 运行轨迹
     * @param before     压缩前计量结果
     * @param after      压缩后计量结果
     * @param beforeSize 压缩前消息条数
     * @param afterSize  压缩后消息条数
     * @param compressed 本次是否真的执行了压缩
     */
    private void publish(ReActTrace trace, TokenPressureMeter.Measurement before, TokenPressureMeter.Measurement after,
                         int beforeSize, int afterSize, boolean compressed) {
        if (trace.hasStreamSink()) trace.pushAgentEvent(new ContextSizeEvent(trace, after.frame.window, afterSize,
                (int) Math.min(Integer.MAX_VALUE, after.inputTokens), compressed, beforeSize, afterSize,
                (int) Math.min(Integer.MAX_VALUE, before.inputTokens), (int) Math.min(Integer.MAX_VALUE, after.inputTokens)));
    }
}
