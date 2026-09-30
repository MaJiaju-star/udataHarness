package com.udata.harness.common.context;

import com.udata.harness.repository.ModelContextAgentSession;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.CompressionStrategy;
import org.noear.solon.ai.agent.react.intercept.ContextCompressionInterceptor;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.chat.message.*;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolResult;

import java.util.*;

/**
 * 长周期任务的上下文规划器：源头级治理 + 显式阶段检查点，不做逐轮历史重写。
 *
 * <p>普通请求沿用稳定前缀并只追加消息，避免每轮重写历史带来的前缀失效；
 * 仅在 Agent 显式请求检查点时，才把旧历史批量收敛为摘要。
 *
 * <p>本类负责四件事，与框架自带压缩的关系如下：
 * <ul>
 *   <li>{@link #govern(ReActTrace)}：把新产生的工具结果投影为有界片段，原文落归档；</li>
 *   <li>{@link #handle(ReActTrace, ToolExchanger)}：实现六个上下文工具的调用语义；</li>
 *   <li>{@link #ensureSnapshot(ReActTrace)} / {@link #refreshSnapshot(ReActTrace)}：维护任务状态快照；</li>
 *   <li>{@link #checkpoint(ReActTrace)}：执行显式阶段检查点，作为框架 token 预算压缩之外的补充。</li>
 * </ul>
 *
 * <p>所有方法都假定运行在主会话的工作线程上，因此直接复用 {@code trace} 携带的会话与工作记忆，
 * 不做跨会话共享或并发保护。
 *
 * @see ToolResultPolicy 工具结果投影规则
 * @see ContextArtifactStore 原始证据归档与恢复
 * @see ContextProtection 提交前的结构校验
 */
public class ContextPlanner {
    /**
     * 任务状态快照消息的元数据标记；同时用于识别与刷新唯一一份快照。
     */
    public static final String TASK_SNAPSHOT = "_udata_task_state";

    /**
     * 本轮已请求检查点的 extra 标记；消费或跳过时即被移除，保证一次请求只生效一次。
     */
    private static final String CHECKPOINT = "_udata_context_checkpoint";

    /**
     * 工具结果投影策略：决定模型实际看到的是全文、引用还是片段。
     */
    private final ToolResultPolicy policy;

    /**
     * 检查点保留的消息条数下限目标；工具组边界可能使其实际值更大。
     */
    private final int retainMessages;

    /**
     * 旧历史摘要策略，内部接入 {@link ObservedSummaryModel} 以观测摘要调用用量。
     */
    private final CompressionStrategy summaryStrategy;

    /**
     * 摘要失败重试次数。
     */
    private final int retries;

    /**
     * 构造上下文规划器。
     *
     * @param maxChars       单个工具结果可投影的最大字符数，取值 1000..16000
     * @param retainMessages 检查点保留的近期消息条数，取值 4..100
     * @param strategy       旧历史摘要策略
     * @param retries        摘要失败重试次数
     * @throws IllegalArgumentException 任一数值参数越界时抛出
     */
    public ContextPlanner(int maxChars, int retainMessages, CompressionStrategy strategy, int retries) {
        if (retainMessages < 4 || retainMessages > 100) throw new IllegalArgumentException("Checkpoint retention must be 4..100 messages");
        policy = new ToolResultPolicy(maxChars);
        this.retainMessages = retainMessages;
        this.summaryStrategy = strategy;
        this.retries = retries;
    }

    /**
     * 治理当前工作记忆中的工具结果：生成有界投影，并保证原文已先行归档。
     *
     * <p>只处理新产生、尚未投影的工具结果，已投影消息保持字节稳定，因此可以安全地在每次推理前重复调用。
     *
     * @param trace 当前 ReAct 执行链路，其会话必须为 {@link ModelContextAgentSession}
     */
    public void govern(ReActTrace trace) {
        ModelContextAgentSession session = session(trace);
        List<ChatMessage> memory = new ArrayList<>(trace.getWorkingMemory().getMessages());
        boolean changed = false;
        Map<String, Map<String, Object>> arguments = new HashMap<>();
        //1. 先扫描出 toolCallId 到调用参数的映射，供后续投影按参数判断是否为同一读取范围。
        for (int i = 0; i < memory.size(); i++) {
            ChatMessage message = memory.get(i);
            if (message instanceof AssistantMessage && message.isToolCalls()) {
                for (ToolCall call : ((AssistantMessage) message).getToolCalls()) arguments.put(call.getId(), call.getArguments());
            }
            if (!(message instanceof ToolMessage)) continue;
            ToolMessage original = (ToolMessage) message;
            ToolMessage projected = policy.project(original, arguments.getOrDefault(original.getToolCallId(), Collections.emptyMap()),
                    memory, session.contextArtifacts());
            if (projected != original) {
                memory.set(i, projected);
                changed = true;
                Map<String, Object> values = new LinkedHashMap<>();
                values.put("tool", original.getName());
                values.put("artifact", projected.getMetadataAs(ToolResultPolicy.ARTIFACT));
                values.put("projection", projected.getMetadataAs(ToolResultPolicy.PROJECTION));
                values.put("originalChars", original.getContent().length());
                values.put("visibleChars", projected.getContent().length());
                session.contextMetrics().event("tool_projection", values);
            }
        }
        if (changed) trace.getWorkingMemory().replaceMessages(memory);
    }

    /**
     * 拦截并执行六个上下文工具调用，取代其默认实现。
     *
     * <p>工具的结果一律通过 {@code call.setToolResult} 写回：参数或状态非法时返回失败文本，
     * 使模型能自行纠正后重试，而不是让整轮推理异常退出。
     *
     * @param trace 当前 ReAct 执行链路
     * @param call  本次工具交换
     * @return 是否已接管该调用；仅为上下文工具时为 {@code true}
     */
    @SuppressWarnings("unchecked")
    public boolean handle(ReActTrace trace, ToolExchanger call) {
        String name = call.getToolName();
        if (!Arrays.asList("context_search", "context_restore", "task_state_get", "task_state_update",
                "context_checkpoint", "context_metrics").contains(name)) return false;
        if (call.getToolResult() != null) return true; // Respect earlier reject/skip decisions.
        ModelContextAgentSession session = session(trace);
        Map<String, Object> args = call.getArgs();
        try {
            Object result;
            switch (name) {
                case "context_search":
                    result = session.contextArtifacts().search(string(args, "query", null), string(args, "tool", ""), number(args, "limit", 5));
                    break;
                case "context_restore":
                    result = session.contextArtifacts().restore(string(args, "id", null), number(args, "offset", 0), number(args, "maxChars", 4000));
                    session.contextMetrics().event("restore", Collections.singletonMap("artifact", args.get("id")));
                    break;
                case "task_state_get": result = session.taskState().get(); break;
                case "task_state_update":
                    if (!(args.get("patch") instanceof Map)) throw new IllegalArgumentException("patch must be an object");
                    Map<String, Object> state = session.taskState().update((Map<String, Object>) args.get("patch"));
                    result = Collections.singletonMap("savedRevision", state.get("revision"));
                    session.contextMetrics().event("task_state_update", (Map<String, Object>) result);
                    break;
                case "context_checkpoint":
                    String reason = string(args, "reason", null);
                    if (reason == null || reason.trim().isEmpty() || reason.length() > 256) throw new IllegalArgumentException("Checkpoint reason must have 1..256 characters");
                    if (!session.taskState().get().containsKey("objective")) throw new IllegalArgumentException("Set task objective and constraints before checkpointing");
                    trace.setExtra(CHECKPOINT, reason);
                    result = Collections.singletonMap("checkpointRequested", reason);
                    break;
                default: result = session.contextMetrics().report();
            }
            call.setToolResult(ToolResult.success(ONode.serialize(result)));
        } catch (RuntimeException e) {
            call.setToolResult(ToolResult.error("Context operation failed: " + e.getMessage()));
        }
        return true;
    }

    /**
     * 确保任务状态快照只注入一次。
     *
     * <p>插入位置固定在模型消息前部并带保护标记；后续状态变化以追加的工具结果可见，
     * 直到下一次检查点或预算压缩才刷新，避免每轮重写前缀。
     *
     * @param trace 当前 ReAct 执行链路
     */
    public void ensureSnapshot(ReActTrace trace) {
        List<ChatMessage> memory = new ArrayList<>(trace.getWorkingMemory().getMessages());
        Map<String, Object> state = session(trace).taskState().get();
        if (!state.isEmpty() && memory.stream().noneMatch(m -> m.hasMetadata(TASK_SNAPSHOT))) {
            memory.add(0, snapshot(state));
            trace.getWorkingMemory().replaceMessages(memory);
        }
    }

    /**
     * 用最新任务状态重写快照，供检查点与预算压缩成功后调用。
     *
     * @param trace 当前 ReAct 执行链路
     */
    public void refreshSnapshot(ReActTrace trace) {
        Map<String, Object> state = session(trace).taskState().get();
        if (state.isEmpty()) return;
        List<ChatMessage> memory = new ArrayList<>(trace.getWorkingMemory().getMessages());
        memory.removeIf(m -> m.hasMetadata(TASK_SNAPSHOT));
        memory.add(0, snapshot(state));
        trace.getWorkingMemory().replaceMessages(memory);
    }

    /**
     * 执行本轮已请求的阶段检查点：把旧历史收敛为摘要，并保留近期完整工具组。
     *
     * <p>未请求、旧内容过少、摘要为空或摘要异常时都会保留原上下文，不做无摘要承接的历史淘汰；
     * 因此本方法只在真正完成摘要时才会缩短上下文。若写盘失败，会回滚工作记忆并抛出异常。
     *
     * @param trace 当前 ReAct 执行链路
     * @throws IllegalStateException 摘要或落盘失败时抛出，调用方应放弃本轮裁剪结果
     */
    public void checkpoint(ReActTrace trace) {
        Object reason = trace.getExtra(CHECKPOINT);
        //1. 未请求检查点直接返回；请求标记由本轮消费或跳过，保证一次请求只生效一次。
        if (reason == null) return;
        List<ChatMessage> before = new ArrayList<>(trace.getWorkingMemory().getMessages());
        List<ChatMessage> variable = new ArrayList<>();
        for (ChatMessage message : before) if (!message.hasMetadata(TASK_SNAPSHOT)) variable.add(message);
        //2. 计算切分点：以“保留条数”为目标，并向前回退到所属 assistant 之前，避免把被保留的工具结果与调用拆开。
        int cut = Math.max(0, variable.size() - retainMessages);
        while (cut > 0 && cut < variable.size() && variable.get(cut) instanceof ToolMessage) cut--;
        //3. 定位最新用户请求原文，并把它从待摘要历史中摘出，稍后按原相对顺序放回，确保请求内容不被压缩改写。
        ChatMessage currentUser = null;
        for (ChatMessage message : variable) {
            if (message instanceof UserMessage && !message.hasMetadata(ContextCompressionInterceptor.META_COMPRESSED)) currentUser = message;
        }
        List<ChatMessage> old = new ArrayList<>(variable.subList(0, cut));
        boolean movedUser = old.remove(currentUser);
        long oldChars = old.stream().mapToLong(m -> m.getContent() == null ? 0 : m.getContent().length()).sum();
        //4. 旧内容过少时跳过，避免为很小收益重建前缀缓存；随后清掉请求标记并记录跳过原因。
        if (old.size() < 2 || oldChars < 2000) {
            trace.getExtras().remove(CHECKPOINT);
            session(trace).contextMetrics().event("checkpoint_skipped", Collections.singletonMap("reason", "Too little old content to justify rebuilding the prefix"));
            return;
        }
        try {
            //5. 重建消息序列：快照置首，其后依次为摘要、被摘出的最新用户请求、保留的近期消息。
            List<ChatMessage> next = new ArrayList<>();
            next.add(snapshot(session(trace).taskState().get()));
            if (!old.isEmpty()) {
                long start = System.currentTimeMillis();
                ChatMessage summary = summaryStrategy.compress(new ObservedSummaryModel(trace.getOptions().getChatModel(), session(trace).contextMetrics(), trace.getRunId()), retries, trace, old);
                session(trace).contextMetrics().event("summary_attempt", Collections.singletonMap("durationMs", System.currentTimeMillis() - start));
                ContextProtection.validateSummary(summary);
                summary.addMetadata(ContextCompressionInterceptor.META_COMPRESSED, true);
                next.add(summary);
            }
            if (movedUser) next.add(currentUser);
            next.addAll(variable.subList(cut, variable.size()));
            //6. 先做结构校验与落盘，再替换工作记忆；任一步失败都保留原上下文前缀。
            ContextProtection.validateToolGroups(before, next);
            session(trace).saveModelContext(next, !old.isEmpty());
            trace.getWorkingMemory().replaceMessages(next);
            trace.getExtras().remove(CHECKPOINT);
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("reason", reason);
            event.put("beforeMessages", before.size());
            event.put("afterMessages", next.size());
            event.put("summarizedMessages", old.size());
            session(trace).contextMetrics().event("checkpoint", event);
        } catch (Throwable e) {
            //7. 回滚工作记忆并记录失败事件：宁可保留过长上下文，也不静默裁剪历史。
            trace.getWorkingMemory().replaceMessages(before);
            session(trace).contextMetrics().event("checkpoint_failed", Collections.singletonMap("reason", String.valueOf(reason)));
            throw new IllegalStateException("Checkpoint failed; original context retained", e);
        }
    }

    /**
     * 把任务状态序列化为快照消息，并标记为受保护前缀。
     *
     * <p>内容显式声明为 Agent 自行维护、完成声明需要证据，因此它不构成任务已完成的证明。
     *
     * @param state 任务状态对象，须非空
     * @return 带 {@link #TASK_SNAPSHOT} 与首部保护标记的用户消息
     */
    private ChatMessage snapshot(Map<String, Object> state) {
        ChatMessage message = ChatMessage.ofUser("Task state snapshot (agent-maintained; completion claims require evidence):\n" + ONode.serialize(state));
        message.addMetadata(TASK_SNAPSHOT, true);
        message.addMetadata(AgentTrace.META_FIRST, true);
        return message;
    }

    /**
     * 取回当前执行链路绑定的会话，作为归档、任务状态与指标的访问入口。
     *
     * @param trace 当前 ReAct 执行链路
     * @return 该运行的模型上下文会话
     */
    private ModelContextAgentSession session(ReActTrace trace) { return (ModelContextAgentSession) trace.getSession(); }

    /**
     * 读取字符串参数；传入类型不是字符串时立即失败，避免把非法参数静默降级为默认值。
     *
     * @param args     工具调用参数
     * @param key      参数名
     * @param fallback 参数缺省时的返回值
     * @return 参数值或默认值
     */
    private static String string(Map<String, Object> args, String key, String fallback) {
        Object value = args.get(key);
        if (value == null) return fallback;
        if (!(value instanceof String)) throw new IllegalArgumentException(key + " must be a string");
        return (String) value;
    }

    /**
     * 读取整数参数；非整数或带小数的数值一律拒绝，避免恢复范围被静默取整。
     *
     * @param args     工具调用参数
     * @param key      参数名
     * @param fallback 参数缺省时的返回值
     * @return 参数值或默认值
     */
    private static int number(Map<String, Object> args, String key, int fallback) {
        Object value = args.get(key);
        if (value == null) return fallback;
        if (!(value instanceof Number) || ((Number) value).doubleValue() != ((Number) value).intValue()) throw new IllegalArgumentException(key + " must be an integer");
        return ((Number) value).intValue();
    }
}
