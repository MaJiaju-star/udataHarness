package com.udata.harness.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.react.intercept.ContextCompressionInterceptor;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class ModelContextAgentSessionTest {
    @TempDir Path tempDir;

    @Test
    void appendsPast400WithoutSlidingOrDeletingHistory() {
        ModelContextAgentSession session = session("append");
        for (int i = 0; i < 450; i++) session.addMessage(ChatMessage.ofUser("message " + i));
        List<ChatMessage> initial = session.getLatestMessages(200);
        session.saveModelContext(initial, false);
        session.addMessage(ChatMessage.ofUser("next"));
        List<ChatMessage> next = session.getLatestMessages(200);
        assertEquals(451, next.size());
        for (int i = 0; i < initial.size(); i++) {
            assertEquals(ChatMessage.toJson(initial.get(i)), ChatMessage.toJson(next.get(i)));
        }
    }

    @Test
    void persistsSummaryAndOnlyAppendsNewArchiveMessagesAfterRestart() {
        ModelContextAgentSession session = session("restart");
        session.addMessage(ChatMessage.ofUser("old task"), ChatMessage.ofAssistant("old reply"));
        session.saveModelContext(Collections.singletonList(summary("task decision")), true);
        // 模拟归档追加成功、下一次上下文检查点尚未落盘时重启。
        session.addMessage(ChatMessage.ofUser("next task"));
        ModelContextAgentSession reopened = session("restart");
        List<ChatMessage> view = reopened.getLatestMessages(200);
        assertEquals(2, view.size());
        assertEquals("task decision", view.get(0).getContent());
        assertEquals("next task", view.get(1).getContent());
        assertEquals(3, reopened.getMessages().size());
        reopened.saveModelContext(view, false);
        assertEquals(2, session("restart").getLatestMessages(200).size());
    }

    @Test
    void nextCompressionReplacesOldSummaryInsteadOfResurrectingRawMessages() {
        ModelContextAgentSession session = session("rolling");
        session.addMessage(ChatMessage.ofUser("original"));
        session.saveModelContext(Collections.singletonList(summary("summary 1")), true);
        session.addMessage(ChatMessage.ofUser("new fact"));
        session.saveModelContext(Collections.singletonList(summary("summary 2 includes new fact")), true);
        ModelContextAgentSession reopened = session("rolling");
        assertEquals(1, reopened.getLatestMessages(200).size());
        assertEquals("summary 2 includes new fact", reopened.getLatestMessages(200).get(0).getContent());
        assertEquals(2, reopened.getMessages().size());
    }

    @Test
    void removingPendingMarkerKeepsSummaryAndNewMessages() {
        ModelContextAgentSession session = session("pending");
        session.addMessage(ChatMessage.ofUser("old task"));
        session.saveModelContext(Collections.singletonList(summary("old task summary")), true);
        ChatMessage question = ChatMessage.ofUser("new task");
        ChatMessage marker = ChatMessage.ofAssistant("waiting approval");
        session.addMessage(question, marker);
        List<ChatMessage> view = session.getLatestMessages(200);
        session.saveModelContext(view, false);
        session.removeLatestMessage(1);
        assertEquals(2, session("pending").getLatestMessages(200).size());
        assertEquals("new task", session("pending").getLatestMessages(200).get(1).getContent());
    }

    @Test
    void migratesLegacyArchiveWithoutApplyingOldWindowAnchor() {
        String dir = tempDir.resolve("legacy").toString();
        FileAgentSession legacy = new FileAgentSession("legacy", dir);
        for (int i = 0; i < 450; i++) legacy.addMessage(ChatMessage.ofUser("message " + i));
        legacy.getContext().put("_udata_history_start", 250);
        legacy.updateSnapshot();
        assertEquals(450, session("legacy").getLatestMessages(200).size());
    }

    @Test
    void corruptionFailsExplicitlyInsteadOfLoadingSummarizedArchive() throws Exception {
        ModelContextAgentSession session = session("corrupt");
        session.addMessage(ChatMessage.ofUser("old task"));
        session.saveModelContext(Collections.singletonList(summary("summary")), true);
        Files.write(contextPath("corrupt"), "broken".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> session("corrupt"));
    }

    @Test
    void failedWriteKeepsLastCommittedView() throws Exception {
        ModelContextAgentSession session = session("write");
        session.addMessage(ChatMessage.ofUser("old task"));
        session.saveModelContext(Collections.singletonList(summary("summary 1")), true);
        Files.createDirectory(contextPath("write").resolveSibling("write.model-context.json.tmp"));
        assertThrows(IllegalStateException.class,
                () -> session.saveModelContext(Collections.singletonList(summary("summary 2")), true));
        assertEquals("summary 1", session.getLatestMessages(200).get(0).getContent());
        assertEquals("summary 1", session("write").getLatestMessages(200).get(0).getContent());
    }

    private ModelContextAgentSession session(String id) {
        return new ModelContextAgentSession(id, tempDir.resolve(id).toString());
    }

    private Path contextPath(String id) {
        return tempDir.resolve(id).resolve(id + ".model-context.json");
    }

    private ChatMessage summary(String text) {
        return ChatMessage.ofUser(text).addMetadata(ContextCompressionInterceptor.META_COMPRESSED, 1);
    }
}
