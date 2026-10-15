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

/** Trace-local state prevents cross-session/subagent contamination; journals contain no message bodies. */
public final class ContextTelemetryInterceptor implements ReActInterceptor {
    static final String LAST_USAGE = "udata.context.last-usage";
    static final String SNAPSHOT = "udata.context.snapshot";
    private static final String LAST_SNAPSHOT = "udata.context.last-snapshot";
    private final ContextArtifactStore store;
    private final ContextCacheConfiguration config;

    public ContextTelemetryInterceptor(ContextArtifactStore store, ContextCacheConfiguration config) {
        this.store = store; this.config = config;
    }

    @Override
    public void onReasonStart(ReActTrace trace, StringBuilder systemPrompt) {
        if (trace.getWorkingMemory() == null || trace.getSession() == null) return;
        Footprint footprint = footprint(trace.getWorkingMemory().getMessages(), systemPrompt.toString(),
                trace.getOptions().getTools(), trace.getProtocolTools(), model(trace));
        Footprint previous = trace.getExtraAs(LAST_SNAPSHOT);
        footprint.view.put("memoryPrefixEstimatedTokens", footprint.shared(previous));
        footprint.view.put("requestTimestamp", System.currentTimeMillis());
        trace.setExtra(SNAPSHOT, footprint);
    }

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
        ContextCostPolicy policy = config.policy(model(trace));
        Map<String, Object> usage = ContextUsage.normalize(response == null ? null : response.getUsage(), policy.prices);
        usage.put("model", model(trace));
        event.put("usage", usage);
        store.recordObservation(trace.getSession().getSessionId(), event);
        trace.setExtra(LAST_USAGE, usage);
        trace.setExtra(LAST_SNAPSHOT, footprint);
    }

    static String model(ReActTrace trace) { return trace.getOptions().getChatModel().getConfig().getNameOrModel(); }

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
            String wire = message.getRole() + ":" + content;
            if (message instanceof ToolMessage) wire += ":" + ((ToolMessage) message).getToolCallId() + ":" + ((ToolMessage) message).getName();
            long tokens = CompressionUtil.countTokens(content) + 4;
            if (message instanceof AssistantMessage) {
                AssistantMessage assistant = (AssistantMessage) message;
                tokens += CompressionUtil.countTokens(assistant.getThinking() == null ? "" : assistant.getThinking());
                if (assistant.getToolCalls() != null) tokens += CompressionUtil.countTokens(ONode.serialize(assistant.getToolCalls()));
                wire += ":" + assistant.getThinking() + ":" + ONode.serialize(assistant.getToolCalls());
            }
            if (message instanceof ToolMessage && ((ToolMessage) message).isMultiModal())
                tokens += CompressionUtil.countTokens(ONode.serialize(((ToolMessage) message).getBlocks()));
            if (message instanceof UserMessage && ((UserMessage) message).isMultiModal())
                tokens += CompressionUtil.countTokens(ONode.serialize(((UserMessage) message).getBlocks()));
            if (message instanceof AssistantMessage && ((AssistantMessage) message).isMultiModal())
                tokens += CompressionUtil.countTokens(ONode.serialize(((AssistantMessage) message).getBlocks()));
            categories.put(category, categories.get(category) + tokens);
            hashes.add(ContextArtifactStore.digest(wire)); sizes.add(tokens);
        }
        Map<String, Object> view = new LinkedHashMap<>();
        view.put("estimatedTokens", categories.values().stream().mapToLong(Long::longValue).sum());
        view.put("categories", categories); view.put("messageCount", messages.size());
        return new Footprint(view, hashes, sizes, model + ":" + ContextArtifactStore.digest(system + definitions));
    }

    static final class Footprint {
        final Map<String, Object> view;
        final List<String> hashes;
        final List<Long> sizes;
        final String fixed;
        Footprint(Map<String, Object> view, List<String> hashes, List<Long> sizes, String fixed) {
            this.view = view; this.hashes = hashes; this.sizes = sizes; this.fixed = fixed;
        }
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
