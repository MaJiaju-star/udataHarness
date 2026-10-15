package com.udata.harness.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UsageAuditRepositoryTest {
    @TempDir
    Path tempDir;

    @Test
    void persistsDeduplicatesAndSeparatesUsers() {
        UsageAuditRepository first = new UsageAuditRepository(tempDir);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "text_replay");
        event.put("usageScope", "model_call");
        event.put("runId", "run-1");
        event.put("reasonId", "reason-1");
        event.put("timestamp", 123L);
        Map<String, Object> usage = new LinkedHashMap<>();
        usage.put("totalTokens", 42);
        event.put("usage", usage);

        first.record("alice", "web-session", "ws", "model-a", event);
        first.record("alice", "web-session", "ws", "model-a", event);
        UsageAuditRepository reopened = new UsageAuditRepository(tempDir);
        reopened.record("alice", "web-session", "ws", "model-a", event);

        Map<String, Object> pending = new LinkedHashMap<>(event);
        pending.remove("reasonId");
        pending.put("usageScope", "run");
        pending.put("type", "run_pending");
        reopened.record("alice", "web-session", "ws", "model-a", pending);

        assertEquals(1, reopened.list("alice", null).size());
        pending.put("type", "run_end");
        reopened.record("alice", "web-session", "ws", "model-a", pending);
        reopened.record("alice", "web-session", "ws", "model-a", pending);
        assertEquals(2, reopened.list("alice", null).size());
        assertEquals(2, reopened.list("alice", "web-session").size());
        assertEquals("model-a", reopened.list("alice", null).get(0).get("selectedModel"));
        assertEquals(0, reopened.list("bob", null).size());
        assertThrows(IllegalArgumentException.class, () -> reopened.list("../alice", null));
    }
}
