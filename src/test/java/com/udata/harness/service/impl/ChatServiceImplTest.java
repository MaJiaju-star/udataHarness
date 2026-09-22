package com.udata.harness.service.impl;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ActionChunk;
import org.noear.solon.ai.agent.react.task.ObservationChunk;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ChatServiceImplTest {
    @TempDir
    Path tempDir;

    @Test
    void alwaysAllowAppliesToSameToolOnlyWithinSameSession() {
        ChatServiceImpl service = new ChatServiceImpl();
        InMemoryAgentSession firstSession = InMemoryAgentSession.of("session-1");
        HITLTask bash = task("call-1", "bash");
        HITLTask anotherBash = task("call-2", "bash");
        HITLTask chart = task("call-3", "render_chart");

        service.rememberAlwaysAllowedTools(firstSession, List.of(bash));

        assertTrue(service.areAllToolsAlwaysAllowed(firstSession, List.of(anotherBash)));
        assertFalse(service.areAllToolsAlwaysAllowed(firstSession, List.of(chart)));
        assertFalse(service.areAllToolsAlwaysAllowed(
                InMemoryAgentSession.of("session-2"), List.of(anotherBash)));
    }

    @Test
    void everyToolInBatchMustBeAllowedBeforeAutomaticResume() {
        ChatServiceImpl service = new ChatServiceImpl();
        InMemoryAgentSession session = InMemoryAgentSession.of("session-1");
        HITLTask bash = task("call-1", "bash");
        HITLTask chart = task("call-2", "render_chart");

        service.rememberAlwaysAllowedTools(session, List.of(bash));

        assertFalse(service.areAllToolsAlwaysAllowed(session, List.of(bash, chart)));
        service.rememberAlwaysAllowedTools(session, List.of(chart));
        assertTrue(service.areAllToolsAlwaysAllowed(session, List.of(bash, chart)));
    }

    @Test
    void normalizesSupportedThinkingDepths() {
        ChatServiceImpl service = new ChatServiceImpl();

        assertEquals("auto", service.normalizeThinkingDepth(null));
        assertEquals("none", service.normalizeThinkingDepth("NONE"));
        assertEquals("low", service.normalizeThinkingDepth("low"));
        assertEquals("medium", service.normalizeThinkingDepth("medium"));
        assertEquals("high", service.normalizeThinkingDepth("high"));
        assertEquals("max", service.normalizeThinkingDepth("max"));
        assertEquals("auto", service.normalizeThinkingDepth("unsupported"));
    }

    @Test
    void recordsSuccessfulFileActivityAndDistinguishesCreateFromModify() throws Exception {
        ChatServiceImpl service = new ChatServiceImpl();
        InMemoryAgentSession session = InMemoryAgentSession.of("session-files");
        ReActTrace trace = new ReActTrace();
        Files.writeString(tempDir.resolve("existing.txt"), "old");

        track(service, session, trace, "read-1", "read", "existing.txt");
        track(service, session, trace, "write-1", "write", "new.txt");
        track(service, session, trace, "write-2", "write", "existing.txt");
        track(service, session, trace, "edit-1", "edit", "existing.txt");

        Object stored = session.getContext().get(ChatServiceImpl.FILE_ACTIVITIES_KEY);
        assertTrue(stored instanceof Map<?, ?>);
        String value = String.valueOf(stored);
        assertTrue(value.contains("type=read"));
        assertTrue(value.contains("type=created"));
        assertTrue(value.contains("type=modified"));
        assertTrue(value.contains("path=new.txt"));
    }

    private void track(ChatServiceImpl service, InMemoryAgentSession session, ReActTrace trace,
                       String callId, String toolName, String filePath) {
        Map<String, Object> args = Map.of("file_path", filePath);
        service.trackFileActivity(tempDir, session, new ActionChunk(trace, callId, toolName, args));
        service.trackFileActivity(tempDir, session, new ObservationChunk(
                trace, callId, toolName, args, ChatMessage.ofTool("ok", toolName, callId), null, 10));
    }

    private static HITLTask task(String callUuid, String toolName) {
        return new HITLTask(callUuid, toolName, Map.of("command", "echo ok"), "需要确认");
    }
}
