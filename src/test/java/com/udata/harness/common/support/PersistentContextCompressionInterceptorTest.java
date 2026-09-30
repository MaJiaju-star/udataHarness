package com.udata.harness.common.support;

import com.udata.harness.repository.ModelContextAgentSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.ReActOptions;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.CompressionStrategy;
import org.noear.solon.ai.agent.react.intercept.ContextCompressionInterceptor;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.harness.agent.AgentDefinition;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PersistentContextCompressionInterceptorTest {
    @TempDir Path tempDir;
    private final ChatModel model = ChatModel.of("http://127.0.0.1:1")
            .provider("openai").model("test-model").contextLength(1000000).build();

    @Test
    void compressesLoadedHistoryAndCarriesSummaryAcrossNextRequestAndRestart() {
        ModelContextAgentSession session = session("history");
        for (int i = 0; i < 25; i++) session.addMessage(ChatMessage.ofUser("history " + i));
        TestTrace trace = trace(session);
        AtomicReference<List<ChatMessage>> summarized = new AtomicReference<>();
        PersistentContextCompressionInterceptor interceptor = interceptor((m, r, t, messages) -> {
            summarized.set(new ArrayList<>(messages));
            return ChatMessage.ofUser("summary of old history");
        });
        interceptor.onReasonStart(trace, new StringBuilder("system"));
        assertNotNull(summarized.get());
        assertTrue(summarized.get().stream().anyMatch(m -> "history 0".equals(m.getContent())));
        assertTrue(trace.getWorkingMemory().getMessages().stream()
                .anyMatch(m -> m.hasMetadata(ContextCompressionInterceptor.META_COMPRESSED)));
        session.addMessage(ChatMessage.ofAssistant("answer"));
        trace.getWorkingMemory().addMessage(session.getMessages().get(25));
        interceptor.onAgentEnd(trace);
        ModelContextAgentSession reopened = session("history");
        reopened.addMessage(ChatMessage.ofUser("next question"));
        List<ChatMessage> next = reopened.getLatestMessages(200);
        assertTrue(next.stream().anyMatch(m -> "summary of old history".equals(m.getContent())));
        assertFalse(next.stream().anyMatch(m -> "history 0".equals(m.getContent())));
        assertEquals("next question", next.get(next.size() - 1).getContent());
        assertEquals(27, reopened.getMessages().size());
    }

    @Test
    void summaryFailureRestoresWholeContextAndDoesNotCommitFallbackTrimming() {
        ModelContextAgentSession session = session("failure");
        for (int i = 0; i < 25; i++) session.addMessage(ChatMessage.ofUser("history " + i));
        TestTrace trace = trace(session);
        PersistentContextCompressionInterceptor interceptor = interceptor((m, r, t, messages) -> null);
        assertThrows(IllegalStateException.class,
                () -> interceptor.onReasonStart(trace, new StringBuilder("system")));
        assertEquals(25, trace.getWorkingMemory().getMessages().size());
        assertEquals(25, session("failure").getLatestMessages(200).size());
    }

    @Test
    void completedToolGroupIsArchivedOnceAndSurvivesCheckpointRecovery() {
        ModelContextAgentSession session = session("tools");
        session.addMessage(ChatMessage.ofUser("read"));
        TestTrace trace = trace(session);
        AssistantMessage reason = new AssistantMessage("", null, false, null, null,
                Arrays.asList(new ToolCall("0", "call-1", "read", "{}", Collections.emptyMap()),
                        new ToolCall("1", "call-2", "read", "{}", Collections.emptyMap())), null);
        trace.newCurrentReasonId();
        trace.setLastReasonMessage(reason);
        trace.getWorkingMemory().addMessage(reason);
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("one", "read", "call-1"));
        trace.getWorkingMemory().addMessage(ChatMessage.ofTool("two", "read", "call-2"));
        PersistentContextCompressionInterceptor interceptor = interceptor((m, r, t, messages) -> ChatMessage.ofUser("summary"));
        interceptor.onActionEnd(trace, Collections.emptyList());
        interceptor.onActionEnd(trace, Collections.emptyList());
        assertEquals(4, session.getMessages().size());
        assertEquals(4, session("tools").getLatestMessages(200).size());
        assertEquals(2, ((AssistantMessage) session("tools").getLatestMessages(200).get(1)).getToolCalls().size());
    }

    @Test
    void childAgentCannotReplaceMainModelContext() {
        ModelContextAgentSession session = session("child");
        session.addMessage(ChatMessage.ofUser("main task"));
        session.saveModelContext(session.getLatestMessages(200), false);
        TestTrace child = new TestTrace(model, session, "child");
        child.getWorkingMemory().addMessage(ChatMessage.ofUser("child task"));
        interceptor((m, r, t, messages) -> ChatMessage.ofUser("summary")).onAgentEnd(child);
        assertEquals("main task", session("child").getLatestMessages(200).get(0).getContent());
    }

    @Test
    void smallerSelectedModelTriggersTokenCompressionBeforeMessageGuard() {
        ModelContextAgentSession session = session("budget");
        for (int i = 0; i < 60; i++) {
            session.addMessage(ChatMessage.ofUser("history " + i + " "
                    + String.join(" ", Collections.nCopies(30, "context"))));
        }
        ChatModel smaller = ChatModel.of("http://127.0.0.1:1").provider("openai")
                .model("small-model").contextLength(2000).build();
        TestTrace trace = new TestTrace(smaller, session, AgentDefinition.AGENT_MAIN);
        trace.getWorkingMemory().addMessage(session.getLatestMessages(200));
        AtomicInteger calls = new AtomicInteger();
        PersistentContextCompressionInterceptor interceptor = new PersistentContextCompressionInterceptor(
                1000, 0.75, 1, 2, (m, r, t, messages) -> {
                    calls.incrementAndGet();
                    assertEquals("small-model", m.getConfig().getModel());
                    return ChatMessage.ofUser("constraints and progress");
                });
        interceptor.onReasonStart(trace, new StringBuilder("system"));
        assertTrue(calls.get() > 0);
        assertTrue(session("budget").getLatestMessages(200).size() < 60);
        assertEquals(60, session("budget").getMessages().size());
    }

    private PersistentContextCompressionInterceptor interceptor(CompressionStrategy strategy) {
        return new PersistentContextCompressionInterceptor(10, 0.75, 1, 2, strategy);
    }

    private ModelContextAgentSession session(String id) {
        return new ModelContextAgentSession(id, tempDir.resolve(id).toString());
    }

    private TestTrace trace(ModelContextAgentSession session) {
        TestTrace trace = new TestTrace(model, session, AgentDefinition.AGENT_MAIN);
        for (ChatMessage message : session.getLatestMessages(200)) {
            message.addMetadata(AgentTrace.META_FIRST, 1);
            trace.getWorkingMemory().addMessage(message);
        }
        return trace;
    }

    private static class TestTrace extends ReActTrace {
        TestTrace(ChatModel model, AgentSession session, String name) {
            prepare(ReActAgent.of(model).name(name).build().getConfig(), new ReActOptions(model), session, null, name);
        }
    }
}
