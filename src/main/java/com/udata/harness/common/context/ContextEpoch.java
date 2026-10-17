package com.udata.harness.common.context;

import com.udata.harness.repository.CompactingFileAgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.FunctionTool;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** A context generation changes for prefix/configuration edits, not ordinary message appends. */
public final class ContextEpoch {
    private static final String STATE = "udata.context.epoch-state";
    public static final String USAGE_EPOCH = "contextEpoch";
    static final String REQUEST_EPOCH = "__udataContextEpoch";
    private ContextEpoch() { }

    public static void identify(ReActTrace trace) {
        if (trace.getSession() instanceof CompactingFileAgentSession) trace.getSession().getMessages();
        if (trace.getWorkingMemory() != null) for (ChatMessage message : trace.getWorkingMemory().getMessages()) ContextMessageIds.ensure(message);
    }

    public static String current(ReActTrace trace) {
        Map<String, Object> state = state(trace);
        if (trace.getSession() instanceof CompactingFileAgentSession) {
            String durable = ((CompactingFileAgentSession) trace.getSession()).checkpoints().epoch();
            if (!durable.equals(state.get("epoch"))) { state.put("epoch", durable); invalidateUsage(trace); }
        }
        return (String) state.get("epoch");
    }

    public static String observe(ReActTrace trace, String systemPrompt) {
        identify(trace);
        Map<String, Object> state = state(trace);
        String signature = signature(trace, systemPrompt);
        List<String> hashes = hashes(trace.getWorkingMemory().getMessages());
        String before = current(trace);
        Object oldSignature = state.get("signature");
        List<String> old = (List<String>) state.get("hashes");
        boolean changed = oldSignature != null && (!signature.equals(oldSignature) || !isPrefix(old, hashes));
        if (trace.getSession() instanceof CompactingFileAgentSession) {
            CompactionCheckpointStore store = ((CompactingFileAgentSession) trace.getSession()).checkpoints();
            try {
                String epoch = store.observeSignature(signature, trace.getSession().getMessages(), trace.getWorkingMemory().getMessages());
                if (changed && epoch.equals(before)) epoch = store.project(trace.getSession().getMessages(), trace.getWorkingMemory().getMessages(), true);
                state.put("epoch", epoch);
            } catch (IOException e) {
                state.put("epoch", "volatile:" + UUID.randomUUID()); // Unknown durable state must not authorize reuse.
            }
        } else if (changed) state.put("epoch", UUID.randomUUID().toString());
        if (!before.equals(state.get("epoch"))) invalidateUsage(trace);
        state.put("signature", signature); state.put("hashes", hashes);
        trace.getOptions().getModelOptions().toolContextPut(REQUEST_EPOCH, state.get("epoch"));
        return (String) state.get("epoch");
    }

    /** Persist projections before replacing active memory. Callers retain originals on IOException. */
    public static void replace(ReActTrace trace, List<ChatMessage> view, boolean historical) throws IOException {
        identify(trace);
        for (ChatMessage message : view) ContextMessageIds.ensure(message);
        Map<String, Object> state = state(trace);
        String before = current(trace);
        if (trace.getSession() instanceof CompactingFileAgentSession) {
            CompactingFileAgentSession session = (CompactingFileAgentSession) trace.getSession();
            state.put("epoch", session.checkpoints().project(session.getMessages(), view, historical));
        } else if (historical) state.put("epoch", UUID.randomUUID().toString());
        trace.getWorkingMemory().replaceMessages(view);
        state.put("hashes", hashes(view));
        if (!before.equals(state.get("epoch"))) invalidateUsage(trace);
        if (trace.getOptions() != null) trace.getOptions().getModelOptions().toolContextPut(REQUEST_EPOCH, state.get("epoch"));
    }

    public static void checkpointCommitted(ReActTrace trace, List<ChatMessage> view) {
        Map<String, Object> state = state(trace);
        if (trace.getSession() instanceof CompactingFileAgentSession)
            state.put("epoch", ((CompactingFileAgentSession) trace.getSession()).checkpoints().epoch());
        else state.put("epoch", UUID.randomUUID().toString());
        for (ChatMessage message : view) ContextMessageIds.ensure(message);
        trace.getWorkingMemory().replaceMessages(view);
        state.put("hashes", hashes(view));
        invalidateUsage(trace);
        trace.getOptions().getModelOptions().toolContextPut(REQUEST_EPOCH, state.get("epoch"));
    }

    static Map<String, Object> validUsage(ReActTrace trace) {
        String epoch = current(trace);
        Map<String, Object> usage = trace.getExtraAs(ContextTelemetryInterceptor.LAST_USAGE);
        return usage != null && epoch.equals(usage.get(USAGE_EPOCH)) ? usage : null;
    }

    private static void invalidateUsage(ReActTrace trace) {
        trace.getExtras().remove(ContextTelemetryInterceptor.LAST_USAGE);
        trace.getExtras().remove(ContextTelemetryInterceptor.LAST_SNAPSHOT);
    }

    private static Map<String, Object> state(ReActTrace trace) {
        Map<String, Object> state = trace.getExtraAs(STATE);
        if (state == null) {
            state = new java.util.LinkedHashMap<>(); state.put("epoch", UUID.randomUUID().toString()); trace.setExtra(STATE, state);
        }
        return state;
    }

    private static List<String> hashes(List<ChatMessage> messages) {
        List<String> hashes = new ArrayList<>();
        for (ChatMessage message : messages) hashes.add(ContextMessageIds.ensure(message) + ":" + CompactionCheckpointStore.messageHash(message));
        return hashes;
    }

    private static boolean isPrefix(List<String> old, List<String> now) {
        return old == null || (old.size() <= now.size() && old.equals(now.subList(0, old.size())));
    }

    private static String signature(ReActTrace trace, String system) {
        StringBuilder key = new StringBuilder(system).append('\n').append(trace.getOptions().getChatModel().getConfig().getNameOrModel());
        key.append('\n').append(trace.getOptions().getChatModel().getConfig().getModel());
        if (trace.getSession() instanceof CompactingFileAgentSession)
            key.append('\n').append(((CompactingFileAgentSession) trace.getSession()).checkpointEnabled());
        key.append('\n').append(trace.getOptions().getChatModel().getConfig().getProvider());
        key.append('\n').append(trace.getOptions().getChatModel().getConfig().getApiUrl());
        for (java.util.Collection<FunctionTool> group : java.util.Arrays.asList(trace.getOptions().getTools(), trace.getProtocolTools()))
            if (group != null) for (FunctionTool tool : group) key.append('\n').append(tool.name()).append(tool.descriptionAndMeta()).append(tool.inputSchema());
        return ContextArtifactStore.digest(key.toString());
    }
}
