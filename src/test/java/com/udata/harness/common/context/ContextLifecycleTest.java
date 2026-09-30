package com.udata.harness.common.context;

import com.udata.harness.common.support.PersistentContextCompressionInterceptor;
import com.udata.harness.repository.ModelContextAgentSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.*;
import org.noear.solon.ai.agent.react.intercept.CompressionStrategy;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.*;
import org.noear.solon.ai.chat.tool.*;
import org.noear.solon.ai.harness.agent.AgentDefinition;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ContextLifecycleTest {
    @TempDir Path directory;
    private final ChatModel model = ChatModel.of("http://127.0.0.1:1").model("test").provider("openai").contextLength(1000000).build();

    @Test void phaseCheckpointKeepsCurrentRequestAndPairsAndCanRestoreModuleA() {
        ModelContextAgentSession session = session("phase");
        String evidence = session.contextArtifacts().put("read", Collections.singletonMap("path", "A.java"), "class A { int evidence; }");
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("objective", "Implement modules A and B");
        state.put("module", "A"); state.put("constraints", Collections.singletonList("Keep public interfaces"));
        state.put("evidence", Collections.singletonList(evidence));
        session.taskState().update(state);
        TestTrace trace = trace(session);
        ChatMessage request = ChatMessage.ofUser("Implement A and B without changing public interfaces");
        trace.getWorkingMemory().addMessage(request);
        for (int i = 0; i < 12; i++) addPair(trace, "call-" + i, "read", "module A detail " + repeat("evidence ", 100));
        session.addMessage(trace.getWorkingMemory().getMessages());
        session.taskState().update(Collections.singletonMap("module", "B"));
        List<ChatMessage> seen = new ArrayList<>();
        PersistentContextCompressionInterceptor interceptor = managed((m, r, t, old) -> {
            seen.addAll(old); return ChatMessage.ofUser("Module A concluded; interface unchanged; evidence " + evidence);
        });
        requestCheckpoint(interceptor, trace);
        interceptor.onReasonStart(trace, new StringBuilder("system"));
        List<ChatMessage> view = session("phase").getLatestMessages(200);
        assertFalse(seen.isEmpty());
        assertFalse(seen.contains(request));
        assertTrue(view.stream().anyMatch(m -> request.getContent().equals(m.getContent())));
        assertTrue(view.get(0).hasMetadata(ContextPlanner.TASK_SNAPSHOT));
        assertTrue(view.get(0).getContent().contains("\"module\":\"B\""));
        assertTrue(view.get(0).getContent().contains("Keep public interfaces"));
        assertEquals(1L, ((Number) session("phase").contextReport().get("generation")).longValue());
        assertEquals(25, session("phase").getMessages().size());
        assertPairs(view);
        assertEquals("class A { int evidence; }", session("phase").contextArtifacts().restore(evidence, 0, 100).get("content"));
        session("phase").taskState().update(Collections.singletonMap("module", "A"));
        assertEquals("A", session("phase").taskState().get().get("module"));
    }

    @Test void checkpointSummaryFailureKeepsDiskAndMemory() {
        ModelContextAgentSession session = session("failed");
        TestTrace trace = populated(session);
        PersistentContextCompressionInterceptor interceptor = managed((m, r, t, old) -> null);
        // Establish a stable snapshot before the failing operation.
        interceptor.onReasonStart(trace, new StringBuilder("system"));
        List<String> before = json(trace.getWorkingMemory().getMessages());
        requestCheckpoint(interceptor, trace);
        assertThrows(IllegalStateException.class, () -> interceptor.onReasonStart(trace, new StringBuilder("system")));
        assertEquals(before, json(trace.getWorkingMemory().getMessages()));
        assertEquals(before, json(session("failed").getLatestMessages(200)));
    }

    @Test void checkpointDiskFailureRetainsOriginalContext() throws Exception {
        ModelContextAgentSession session = session("disk");
        TestTrace trace = populated(session);
        PersistentContextCompressionInterceptor interceptor = managed((m, r, t, old) -> ChatMessage.ofUser("summary"));
        interceptor.onReasonStart(trace, new StringBuilder("system"));
        List<String> before = json(trace.getWorkingMemory().getMessages());
        Files.createDirectory(directory.resolve("disk").resolve("disk.model-context.json.tmp"));
        requestCheckpoint(interceptor, trace);
        assertThrows(IllegalStateException.class, () -> interceptor.onReasonStart(trace, new StringBuilder("system")));
        assertEquals(before, json(trace.getWorkingMemory().getMessages()));
        assertEquals(before, json(session("disk").getLatestMessages(200)));
    }

    @Test void tinyCheckpointsAreSkippedAndUpdatesDoNotRewriteExistingSnapshot() {
        ModelContextAgentSession session = session("stable");
        session.taskState().update(Collections.singletonMap("objective", "Fix issue"));
        TestTrace trace = trace(session);
        trace.getWorkingMemory().addMessage(ChatMessage.ofUser("Fix issue"));
        AtomicInteger summaries = new AtomicInteger();
        PersistentContextCompressionInterceptor interceptor = managed((m, r, t, old) -> { summaries.incrementAndGet(); return ChatMessage.ofUser("summary"); });
        interceptor.onReasonStart(trace, new StringBuilder("system"));
        String snapshot = ChatMessage.toJson(trace.getWorkingMemory().getMessages().get(0));
        session.taskState().update(Collections.singletonMap("module", "B"));
        requestCheckpoint(interceptor, trace);
        interceptor.onReasonStart(trace, new StringBuilder("system"));
        assertEquals(0, summaries.get());
        assertEquals(snapshot, ChatMessage.toJson(trace.getWorkingMemory().getMessages().get(0)));
    }

    @Test void mainOnlyToolsValidateArgumentsAndCannotOverwriteRejectedResult() {
        ModelContextAgentSession session = session("tools");
        TestTrace trace = trace(session);
        PersistentContextCompressionInterceptor interceptor = managed((m, r, t, old) -> ChatMessage.ofUser("summary"));
        ToolExchanger invalid = new ToolExchanger("restore", "context_restore", Collections.singletonMap("id", "../escape"));
        interceptor.onToolCallStart(trace, invalid);
        assertTrue(invalid.getToolResult().isError());
        ToolExchanger rejected = new ToolExchanger("restore", "context_restore", Collections.emptyMap());
        ToolResult rejection = ToolResult.error("rejected"); rejected.setToolResult(rejection);
        interceptor.onToolCallStart(trace, rejected);
        assertSame(rejection, rejected.getToolResult());
        TestTrace child = new TestTrace(model, session, "child");
        ToolExchanger call = new ToolExchanger("state", "task_state_get", Collections.emptyMap());
        interceptor.onToolCallStart(child, call);
        assertNull(call.getToolResult());
    }

    @Test void actionArchivesOriginalOutputBeforeProjectionAndReopeningKeepsBothViews() {
        ModelContextAgentSession session = session("raw");
        TestTrace trace = trace(session);
        String text = repeat("long test output\n", 1000) + "ERROR middle failure\n";
        AssistantMessage reason = addPair(trace, "log", "bash", text);
        trace.setLastReasonMessage(reason);
        PersistentContextCompressionInterceptor interceptor = managed((m, r, t, old) -> ChatMessage.ofUser("summary"));
        interceptor.onActionEnd(trace, Collections.emptyList());
        ModelContextAgentSession reopened = session("raw");
        assertEquals(text, reopened.getMessages().get(1).getContent());
        ToolMessage visible = (ToolMessage) reopened.getLatestMessages(200).get(1);
        assertTrue(visible.getContent().length() <= 1000);
        String id = visible.getMetadataAs(ToolResultPolicy.ARTIFACT);
        assertEquals(text.substring(0, 16000), reopened.contextArtifacts().restore(id, 0, 16000).get("content"));
    }

    @Test void budgetCompressionProtectsAndRefreshesDurableTaskConstraints() {
        ModelContextAgentSession session = session("budget-state");
        TestTrace trace = populated(session);
        session.taskState().update(Collections.singletonMap("constraints", Collections.singletonList("Preserve API")));
        PersistentContextCompressionInterceptor interceptor = new PersistentContextCompressionInterceptor(
                10, 0.75, 1, 2, (m, r, t, old) -> {
                    assertTrue(old.stream().noneMatch(item -> item.hasMetadata(ContextPlanner.TASK_SNAPSHOT)));
                    return ChatMessage.ofUser("Summarized progress");
                }).contextManagement(1000, 4);
        interceptor.onReasonStart(trace, new StringBuilder("system"));
        assertTrue(trace.getWorkingMemory().getMessages().get(0).getContent().contains("Preserve API"));
        session.taskState().update(Collections.singletonMap("constraints", Collections.singletonList("Use JDK8")));
        for (int i = 0; i < 25; i++) trace.getWorkingMemory().addMessage(ChatMessage.ofAssistant("new detail " + i));
        interceptor.onReasonStart(trace, new StringBuilder("system"));
        List<ChatMessage> view = session("budget-state").getLatestMessages(200);
        assertTrue(view.get(0).hasMetadata(ContextPlanner.TASK_SNAPSHOT));
        assertTrue(view.get(0).getContent().contains("Preserve API"));
        assertTrue(view.get(0).getContent().contains("Use JDK8"));
        assertTrue(((Number) session("budget-state").contextReport().get("generation")).longValue() >= 2);
        assertPairs(view);
    }

    @Test void repeatedBudgetCompressionKeepsLatestRequestAndPendingState() {
        ModelContextAgentSession session = session("protected-budget");
        session.taskState().update(Collections.singletonMap("objective", "Implement A and B"));
        session.taskState().update(Collections.singletonMap("pending", Collections.singletonList("B tests still failing; do not deliver")));
        TestTrace trace = trace(session);
        trace.getWorkingMemory().addMessage(ChatMessage.ofUser("Earlier request"));
        for (int i = 0; i < 10; i++) addPair(trace, "early-" + i, "read", "early code");
        ChatMessage current = ChatMessage.ofUser("Fix B without changing API\nKeep the migration reversible");
        trace.getWorkingMemory().addMessage(current);
        for (int i = 0; i < 12; i++) addPair(trace, "late-" + i, "read", "late code");
        AtomicInteger summaries = new AtomicInteger();
        PersistentContextCompressionInterceptor interceptor = new PersistentContextCompressionInterceptor(10, 0.75, 1, 2,
                (m, r, t, old) -> {
                    assertFalse(old.contains(current));
                    summaries.incrementAndGet();
                    return ChatMessage.ofUser("Progress summarized");
                }).contextManagement(1000, 4);
        interceptor.onReasonStart(trace, new StringBuilder("system"));
        for (int i = 0; i < 15; i++) addPair(trace, "again-" + i, "read", "new code");
        interceptor.onReasonStart(trace, new StringBuilder("system"));
        assertTrue(summaries.get() >= 2);
        List<ChatMessage> view = session("protected-budget").getLatestMessages(200);
        assertTrue(view.stream().anyMatch(message -> current.getContent().equals(message.getContent())));
        assertTrue(view.get(0).getContent().contains("B tests still failing; do not deliver"));
        assertPairs(view);
        List<String> stable = json(trace.getWorkingMemory().getMessages());
        interceptor.onReasonStart(trace, new StringBuilder("system"));
        assertEquals(stable, json(trace.getWorkingMemory().getMessages()));
    }

    private TestTrace populated(ModelContextAgentSession session) {
        session.taskState().update(Collections.singletonMap("objective", "Implement A and B"));
        TestTrace trace = trace(session);
        trace.getWorkingMemory().addMessage(ChatMessage.ofUser("Current user constraints"));
        for (int i = 0; i < 12; i++) addPair(trace, "call-" + i, "read", "old detail " + repeat("evidence ", 100));
        session.addMessage(trace.getWorkingMemory().getMessages());
        return trace;
    }

    private AssistantMessage addPair(TestTrace trace, String id, String tool, String content) {
        AssistantMessage reason = new AssistantMessage("", null, false, null, null,
                Collections.singletonList(new ToolCall(id, id, tool, "{}", Collections.emptyMap())), null);
        trace.getWorkingMemory().addMessage(reason);
        trace.getWorkingMemory().addMessage(new ToolMessage(ToolResult.success(content), tool, id, false));
        return reason;
    }

    private void requestCheckpoint(PersistentContextCompressionInterceptor interceptor, TestTrace trace) {
        ToolExchanger call = new ToolExchanger("checkpoint", "context_checkpoint", Collections.singletonMap("reason", "A completed; switch to B"));
        interceptor.onToolCallStart(trace, call);
        assertFalse(call.getToolResult().isError(), call.getToolResult().getContent());
    }

    private void assertPairs(List<ChatMessage> messages) {
        Set<String> pending = new HashSet<>();
        for (ChatMessage message : messages) {
            if (message instanceof AssistantMessage && message.isToolCalls()) {
                assertTrue(pending.isEmpty());
                for (ToolCall call : ((AssistantMessage) message).getToolCalls()) pending.add(call.getId());
            } else if (message instanceof ToolMessage) assertTrue(pending.remove(((ToolMessage) message).getToolCallId()));
            else assertTrue(pending.isEmpty());
        }
        assertTrue(pending.isEmpty());
    }

    private List<String> json(List<ChatMessage> messages) {
        List<String> result = new ArrayList<>();
        for (ChatMessage message : messages) {
            ChatMessage clone = ChatMessage.fromJson(ChatMessage.toJson(message));
            clone.getMetadata().remove(org.noear.solon.ai.agent.AgentTrace.META_FIRST);
            clone.getMetadata().remove("token_size");
            result.add(ChatMessage.toJson(clone));
        }
        return result;
    }

    private PersistentContextCompressionInterceptor managed(CompressionStrategy strategy) {
        return new PersistentContextCompressionInterceptor(1000, 0.75, 1, 2, strategy).contextManagement(1000, 4);
    }
    private ModelContextAgentSession session(String id) { return new ModelContextAgentSession(id, directory.resolve(id).toString()); }
    private TestTrace trace(ModelContextAgentSession session) { return new TestTrace(model, session, AgentDefinition.AGENT_MAIN); }
    private static String repeat(String value, int count) { StringBuilder b = new StringBuilder(); for (int i = 0; i < count; i++) b.append(value); return b.toString(); }
    private static class TestTrace extends ReActTrace {
        TestTrace(ChatModel model, AgentSession session, String name) {
            prepare(ReActAgent.of(model).name(name).build().getConfig(), new ReActOptions(model), session, null, name);
        }
    }
}
