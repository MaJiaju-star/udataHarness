package com.udata.harness.common.context;

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Stateless projection: no execution caching and no rewrites of already-sent history. */
public class ToolResultContextInterceptor implements ReActInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(ToolResultContextInterceptor.class);
    private static final Set<String> TOOLS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "read", "bash", "grep", "glob", "ls", "webfetch", "websearch", "codesearch", "lsp")));
    private final ContextArtifactStore store;
    private final ContextManagementOptions options;

    public ToolResultContextInterceptor(ContextArtifactStore store, ContextManagementOptions options) {
        this.store = store;
        this.options = options;
    }

    @Override
    public void onToolCallEnd(ReActTrace trace, ToolExchanger exchanger,
                              ChatMessage observation, Throwable error, long durationMs) {
        ToolResult result = exchanger.getToolResult();
        if (error != null || observation == null || trace.getSession() == null
                || trace.getSession().isPending() || exchanger.isReturnDirect()
                || observation.hasMetadata(AgentTrace.META_FIRST)
                || !eligible(exchanger.getToolName(), result)) return;
        String content = result.getContent();
        String reason = null;
        int preview = options.previewChars;
        if ("read".equals(exchanger.getToolName()) && hasVisibleRead(trace, exchanger, content)) {
            reason = "duplicate_read";
            preview = 0;
        } else if (content.length() > options.singleResultChars) {
            reason = "single_result_limit";
        }
        if (reason == null) return;
        String wireId = observation instanceof ToolMessage
                ? ((ToolMessage) observation).getToolCallId()
                : exchanger.getCallId();
        String projection = project(trace, wireId, exchanger.getToolName(), content, reason, preview);
        if (projection != null) exchanger.setResult(projection);
    }

    /** ActionTask has committed this batch to WorkingMemory, but Reason has not sent it yet. */
    @Override
    public void onActionEnd(ReActTrace trace, Collection<ToolExchanger> toolCalls) {
        if (trace.getSession() == null || trace.getSession().isPending()
                || trace.getWorkingMemory() == null) return;
        AssistantMessage reason = trace.getLastReasonMessage();
        // Text-mode observations have no wire call IDs: keep their batch unchanged.
        if (reason == null || reason.getToolCalls() == null || reason.getToolCalls().isEmpty()) return;
        Set<String> batchIds = new HashSet<>();
        Map<String, ToolExchanger> exchangersByUuid = new HashMap<>();
        for (ToolExchanger exchanger : toolCalls) exchangersByUuid.put(exchanger.getCallId(), exchanger);
        Map<String, ToolExchanger> exchangersById = new HashMap<>();
        for (ToolCall call : reason.getToolCalls()) {
            batchIds.add(call.getId());
            exchangersById.put(call.getId(), exchangersByUuid.get(call.getUuid()));
        }
        List<ChatMessage> messages = trace.getWorkingMemory().getMessages();
        int batchStart = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) == reason) {
                batchStart = i + 1;
                break;
            }
        }
        // A wire ID alone is insufficient: compatible providers may reuse it in older history.
        if (batchStart < 0) return;
        List<ToolMessage> candidates = new ArrayList<>();
        long totalChars = 0;
        for (ChatMessage message : messages.subList(batchStart, messages.size())) {
            if (!(message instanceof ToolMessage)) continue;
            ToolMessage tool = (ToolMessage) message;
            if (!batchIds.contains(tool.getToolCallId())) continue;
            if (tool.getContent() != null) totalChars += tool.getContent().length();
            if (!tool.isReturnDirect() && !tool.isError() && !tool.hasMetadata(AgentTrace.META_FIRST)
                    && TOOLS.contains(tool.getName()) && !tool.isMultiModal()
                    && tool.getBlocks().stream().allMatch(block -> block instanceof TextBlock)
                    && isRawText(tool.getContent())) candidates.add(tool);
        }
        if (totalChars <= options.batchResultChars) return;
        candidates.sort(Comparator.comparingInt((ToolMessage value) -> value.getContent().length()).reversed());
        Map<String, ToolMessage> replacements = new HashMap<>();
        for (ToolMessage tool : candidates) {
            if (totalChars <= options.batchResultChars) break;
            String projection = project(trace, tool.getToolCallId(), tool.getName(), tool.getContent(),
                    "batch_result_limit", options.previewChars);
            if (projection == null) continue;
            ToolResult replacementResult = ToolResult.success(projection);
            if (tool.getMetadata() != null) tool.getMetadata().forEach((key, value) -> {
                if (!"token_size".equals(key)) replacementResult.metas().put(key, value);
            });
            ToolMessage replacement = ChatMessage.ofTool(replacementResult, tool.getName(), tool.getToolCallId(), false);
            if (tool.getMetadata() != null) {
                tool.getMetadata().forEach((key, value) -> {
                    if (!"token_size".equals(key)) replacement.addMetadata(key, value);
                });
            }
            replacements.put(tool.getToolCallId(), replacement);
            ToolExchanger exchanger = exchangersById.get(tool.getToolCallId());
            if (exchanger != null) exchanger.setResult(projection);
            totalChars -= tool.getContent().length() - projection.length();
        }
        if (!replacements.isEmpty()) {
            List<ChatMessage> projected = new ArrayList<>(messages.size());
            for (int i = 0; i < messages.size(); i++) {
                ChatMessage message = messages.get(i);
                ToolMessage replacement = i >= batchStart && message instanceof ToolMessage
                        ? replacements.get(((ToolMessage) message).getToolCallId()) : null;
                projected.add(replacement == null ? message : replacement);
            }
            trace.getWorkingMemory().replaceMessages(projected);
        }
        // Non-reducible results may exceed the soft budget. Existing compression remains the safety net.
    }

    private String project(ReActTrace trace, String invocationId, String tool, String content,
                           String reason, int previewChars) {
        try {
            // Some compatible providers reuse wire call IDs across turns. Scope by run and Reason.
            String scopedInvocation = trace.getRunId() + ":" + trace.getCurrentTurnId() + ":" + invocationId;
            ContextArtifactStore.Artifact artifact = store.save(trace.getSession().getSessionId(),
                    scopedInvocation, tool, content, reason, previewChars);
            return artifact.projection.length() < content.length() ? artifact.projection : null;
        } catch (IOException e) {
            // Do not log payloads, arguments, or filesystem error messages containing user data.
            LOG.warn("Context artifact persistence failed; retaining tool result ({})", e.getClass().getSimpleName());
            return null;
        }
    }

    private static boolean eligible(String tool, ToolResult result) {
        return TOOLS.contains(tool) && result != null && !result.isError()
                && result.getBlocks().stream().allMatch(block -> block instanceof TextBlock)
                && isRawText(result.getContent());
    }

    private static boolean isRawText(String content) {
        return content != null && !content.isEmpty() && !content.startsWith("<context-artifact id=\"");
    }

    private static boolean hasVisibleRead(ReActTrace trace, ToolExchanger current, String content) {
        if (trace.getWorkingMemory() == null) return false;
        Map<String, ToolCall> reads = new HashMap<>();
        String args = canonicalArguments(current.getArgs());
        for (ChatMessage message : trace.getWorkingMemory().getMessages()) {
            if (message instanceof AssistantMessage) {
                List<ToolCall> calls = ((AssistantMessage) message).getToolCalls();
                if (calls != null) {
                    for (ToolCall call : calls) {
                        if ("read".equals(call.getName()) && args.equals(canonicalArguments(call.getArguments()))) {
                            reads.put(call.getId(), call);
                        }
                    }
                }
            } else if (message instanceof ToolMessage) {
                ToolMessage previous = (ToolMessage) message;
                if ("read".equals(previous.getName()) && !previous.isError()
                        && reads.containsKey(previous.getToolCallId())
                        && content.equals(previous.getContent())) return true;
            }
        }
        return false;
    }

    private static String canonicalArguments(Map<String, Object> args) {
        Map<String, Object> canonical = new TreeMap<>();
        if (args != null) args.forEach((key, value) -> {
            if (!key.startsWith("__")) canonical.put(key, value);
        });
        return ONode.serialize(canonical);
    }
}
