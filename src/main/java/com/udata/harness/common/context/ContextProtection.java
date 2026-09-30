package com.udata.harness.common.context;

import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.react.intercept.ContextCompressionInterceptor;
import org.noear.solon.ai.chat.message.*;
import org.noear.solon.ai.chat.tool.ToolCall;
import java.util.*;

/**
 * 压缩期间的保护前缀与提交前校验：确保“历史退出上下文必须有摘要承接”。
 *
 * <p>临时保护前缀只存在于压缩过程内：把任务状态快照与最新用户请求原文提到消息前部，
 * 以 {@code META_FIRST} 标记保护，使框架压缩无法删除它们；压缩结束（无论成败）后标记会被移除，
 * 普通请求的上下文中<b>不会</b>残留保护标记，所以每轮请求的排版保持稳定。</p>
 *
 * <p>这里只做结构校验（摘要非空且不能是工具结果或工具调用、保留结果必须有对应调用、
 * 已完整的工具组不能只剩调用），它<b>不证明</b>自然语言摘要没有遗漏或误述。</p>
 */
public final class ContextProtection {
    /**
     * 压缩前的消息快照，用于回滚与校验。
     */
    private final List<ChatMessage> before;

    /**
     * 被保护的最新用户请求原文；无符合条件的用户消息时为 {@code null}。
     */
    private final ChatMessage user;

    /**
     * 记录压缩前的上下文，并定位需要保护的用户请求。
     *
     * <p>取最后一条普通用户消息：跳过任务状态快照与已压缩摘要，同时要求正文非空，
     * 避免把空占位消息当作“当前请求”保护起来。</p>
     *
     * @param messages 压缩前的完整消息列表
     */
    public ContextProtection(List<ChatMessage> messages) {
        before = new ArrayList<>(messages);
        ChatMessage latest = null;
        for (ChatMessage message : messages) {
            if (message instanceof UserMessage && !message.hasMetadata(ContextPlanner.TASK_SNAPSHOT)
                    && !message.hasMetadata(ContextCompressionInterceptor.META_COMPRESSED)
                    && message.getContent() != null) latest = message;
        }
        user = latest;
    }

    /**
     * 构造压缩输入：快照置首、用户请求紧随其后并标记为受保护，其余消息保持原相对顺序。
     *
     * @return 重排后的消息列表，可直接交给压缩策略
     */
    public List<ChatMessage> prepare() {
        //1. 任务状态快照必须始终存活，置于最前。
        List<ChatMessage> result = new ArrayList<>();
        for (ChatMessage message : before) if (message.hasMetadata(ContextPlanner.TASK_SNAPSHOT)) result.add(message);
        //2. 最新用户请求加 META_FIRST 保护，使其在压缩中不可被删除。
        if (user != null) { user.addMetadata(AgentTrace.META_FIRST, true); result.add(user); }
        //3. 其余消息按原顺序追加（跳过已单独放置的快照与用户请求）。
        for (ChatMessage message : before) if (message != user && !message.hasMetadata(ContextPlanner.TASK_SNAPSHOT)) result.add(message);
        return result;
    }

    /**
     * 恢复压缩后的消息顺序，并校验结果结构。
     *
     * <p>未发生压缩时直接返回原上下文，保证“无摘要不淘汰历史”；发生压缩时把用户请求按原时间位置插回，
     * 使压缩后的相对顺序与压缩前一致，而不是简单地重新放到开头。</p>
     *
     * @param after      压缩策略输出的消息列表
     * @param compressed 本次是否真正生成了摘要
     * @return 可提交给模型视图的消息列表
     * @throws IllegalStateException 压缩丢失了当前用户请求或拆散了工具调用组时抛出
     */
    public List<ChatMessage> finish(List<ChatMessage> after, boolean compressed) {
        //1. 无论成败先解除保护标记，避免标记泄漏到普通请求中。
        if (user != null) user.getMetadata().remove(AgentTrace.META_FIRST);
        if (!compressed) return new ArrayList<>(before);
        if (user != null && !after.contains(user)) throw new IllegalStateException("Compression lost current user request");
        List<ChatMessage> result = new ArrayList<>(after);
        if (user != null) {
            //2. 先把用户请求摘出，再根据原序列定位它后面第一个幸存消息，插回到对应位置。
            result.remove(user);
            // Preserve chronological position among survivors; a new summary replaces older history.
            int position = result.size();
            for (int i = before.indexOf(user) + 1; i < before.size(); i++) {
                int survivor = result.indexOf(before.get(i));
                if (survivor >= 0) { position = survivor; break; }
            }
            result.add(position, user);
        }
        //3. 提交前做工具组结构校验，拒绝孤儿结果与被拆散的调用组。
        validateToolGroups(before, result);
        return result;
    }

    /**
     * 压缩失败或放弃时回滚：仅移除保护标记，原上下文保持不变。
     */
    public void rollback() { if (user != null) user.getMetadata().remove(AgentTrace.META_FIRST); }

    /**
     * 校验工具调用组的完整性：拒绝孤儿结果，也拒绝把原本完整的工具交换拆散。
     *
     * <p>两条规则分别是：保留的结果必须有对应调用；原本有结果的调用不能被单独留下。
     * 这是结构校验，不判断调用是否成功。</p>
     *
     * @param before 压缩前的消息列表
     * @param after  压缩后的消息列表
     * @throws IllegalStateException 出现孤儿结果或工具交换被拆散时抛出
     */
    public static void validateToolGroups(List<ChatMessage> before, List<ChatMessage> after) {
        //1. 分别收集压缩前已存在的、压缩后保留的结果，以及压缩后保留的调用。
        Set<String> originalResults = new HashSet<>(), results = new HashSet<>(), calls = new HashSet<>();
        for (ChatMessage m : before) if (m instanceof ToolMessage) originalResults.add(((ToolMessage) m).getToolCallId());
        for (ChatMessage m : after) {
            if (m instanceof ToolMessage) results.add(((ToolMessage) m).getToolCallId());
            if (m instanceof AssistantMessage && m.isToolCalls())
                for (ToolCall call : ((AssistantMessage) m).getToolCalls()) calls.add(call.getId());
        }
        //2. 规则一：保留的每个工具结果都必须能找到对应调用。
        if (!calls.containsAll(results)) throw new IllegalStateException("Compression retained orphan tool results");
        //3. 规则二：原本有结果的调用不能被单独留下。
        for (String call : calls) if (originalResults.contains(call) && !results.contains(call))
            throw new IllegalStateException("Compression split a tool exchange");
    }

    /**
     * 校验摘要可用性。
     *
     * <p>只做结构检查：非空、非工具调用、非工具结果。它无法证明摘要内容无损，
     * 因此恢复历史证据时仍需核对当前文件与测试结果。</p>
     *
     * @param summary 压缩策略生成的摘要消息
     * @throws IllegalStateException 摘要为空或不是普通文本消息时抛出
     */
    public static void validateSummary(ChatMessage summary) {
        if (summary == null || summary.getContent() == null || summary.getContent().trim().isEmpty()
                || summary.isToolCalls() || summary instanceof ToolMessage)
            throw new IllegalStateException("Compression must produce a nonempty plain-text summary");
    }
}
