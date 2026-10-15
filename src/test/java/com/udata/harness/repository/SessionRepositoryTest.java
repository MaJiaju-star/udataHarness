package com.udata.harness.repository;

import com.udata.harness.common.domain.SessionMetadata;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.chat.message.ChatMessage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsMetadataAndMessagesAcrossStoreInstances() {
        SessionRepository first = new SessionRepository(tempDir);
        SessionMetadata created = first.create("alice", "Implement streaming", "test-model", "ws-project");
        first.getSession(created.getSessionId()).addMessage(ChatMessage.ofUser("hello"));

        SessionRepository reopened = new SessionRepository(tempDir);
        SessionMetadata loaded = reopened.read("alice", created.getSessionId());

        assertEquals("Implement streaming", loaded.getTitle());
        assertEquals("test-model", loaded.getModel());
        assertEquals("ws-project", loaded.getWorkspaceId());
        assertEquals("hello", reopened.getSession(created.getSessionId()).getMessages().get(0).getContent());
        assertFalse(reopened.list("alice").isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> reopened.getSession("bob", created.getSessionId()));
    }

    @Test
    void checkpointProjectsModelHistoryWithoutDeletingRawTranscript() throws IOException {
        SessionRepository first = new SessionRepository(tempDir, true);
        SessionMetadata created = first.create("alice", "Long task", "test-model");
        CompactingFileAgentSession session = (CompactingFileAgentSession) first.getSession(created.getSessionId());
        session.addMessage(ChatMessage.ofUser("first"));
        session.addMessage(ChatMessage.ofAssistant("answer"));
        session.checkpoints().save(session.getMessages(), 2, "<compacted-summary>facts</compacted-summary>", "test-model");
        session.addMessage(ChatMessage.ofUser("latest"));

        SessionRepository reopened = new SessionRepository(tempDir, true);
        CompactingFileAgentSession loaded = (CompactingFileAgentSession) reopened.getSession(created.getSessionId());
        assertEquals(3, loaded.getMessages().size());
        assertEquals(2, loaded.getLatestMessages(200).size());
        assertTrue(loaded.getLatestMessages(200).get(0).getContent().contains("facts"));
        assertEquals("latest", loaded.getLatestMessages(200).get(1).getContent());
        loaded.removeLatestMessage(2);
        assertEquals(1, loaded.getLatestMessages(200).size());
        assertEquals("first", loaded.getLatestMessages(200).get(0).getContent());
    }

    @Test
    void rejectsPathTraversalSessionIds() {
        SessionRepository store = new SessionRepository(tempDir);
        assertThrows(IllegalArgumentException.class, () -> store.getSession("../outside"));
    }

    @Test
    void listSkipsOrphanDirectoriesWithoutHidingStrictReadErrors() throws IOException {
        SessionRepository store = new SessionRepository(tempDir);
        SessionMetadata valid = store.create("alice", "Valid", "test-model");
        String orphanId = "web-9451cd6b-16b0-437f-9f7d-abd4469237cd";
        Files.createDirectories(store.getSessionsRoot().resolve("alice")
                .resolve("sessions").resolve(orphanId));

        assertEquals(1, store.list("alice").size());
        assertEquals(valid.getSessionId(), store.list("alice").get(0).getSessionId());
        assertThrows(IllegalArgumentException.class, () -> store.read("alice", orphanId));
    }

    @Test
    void persistsSessionPermissionMode() {
        SessionRepository store = new SessionRepository(tempDir);
        SessionMetadata created = store.create("alice", "Permissions", "test-model");
        assertEquals("standard", created.getPermissionMode());

        store.updatePermissionMode("alice", created.getSessionId(), "full");

        SessionRepository reopened = new SessionRepository(tempDir);
        assertEquals(
                "full",
                reopened.read("alice", created.getSessionId()).getPermissionMode());
        reopened.updatePermissionMode("alice", created.getSessionId(), "unknown");
        assertEquals(
                "standard",
                reopened.read("alice", created.getSessionId()).getPermissionMode());
    }

    @Test
    void usesFirstUserPromptAsTitleWithoutOverwritingItLater() {
        SessionRepository store = new SessionRepository(tempDir);
        SessionMetadata created = store.create("alice", "新的编码任务", "test-model");

        store.applyFirstPromptTitle("alice", created.getSessionId(), "请分析这个项目的会话模块");
        assertEquals(
                "请分析这个项目的会话模块",
                store.read("alice", created.getSessionId()).getTitle());

        store.applyFirstPromptTitle("alice", created.getSessionId(), "这是第二个问题");
        assertEquals(
                "请分析这个项目的会话模块",
                store.read("alice", created.getSessionId()).getTitle());
    }

    @Test
    void supportsManualSessionRename() {
        SessionRepository store = new SessionRepository(tempDir);
        SessionMetadata created = store.create("alice", "新的编码任务", "test-model");

        SessionMetadata renamed = store.updateTitle("alice", created.getSessionId(), "自定义标题");

        assertEquals("自定义标题", renamed.getTitle());
        assertEquals("自定义标题", store.read("alice", created.getSessionId()).getTitle());
    }
}
