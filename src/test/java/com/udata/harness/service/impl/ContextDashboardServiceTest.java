package com.udata.harness.service.impl;

import com.udata.harness.common.context.ContextArtifactStore;
import com.udata.harness.common.context.ContextCacheConfiguration;
import com.udata.harness.common.domain.SessionMetadata;
import com.udata.harness.repository.SessionRepository;
import com.udata.harness.repository.UsageAuditRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class ContextDashboardServiceTest {
    @TempDir Path directory;

    @Test
    void enforcesOwnershipAndReturnsOnlyRequestedSessionTailWithoutRawBodies() throws Exception {
        SessionRepository sessions = new SessionRepository(directory);
        SessionMetadata a = sessions.create("alice", "A", "model");
        SessionMetadata b = sessions.create("alice", "B", "model");
        ContextArtifactStore artifacts = new ContextArtifactStore(directory.resolve("users/alice/context-artifacts/default"));
        for (int i = 0; i < 3; i++) {
            Map<String, Object> event = new java.util.LinkedHashMap<>();
            event.put("id", "request-" + i); event.put("estimatedTokens", i + 100);
            artifacts.recordObservation(a.getSessionId(), event);
        }
        artifacts.save(a.getSessionId(), "call", "read", "PRIVATE_BODY", "limit", 2);
        ContextDashboardService service = new ContextDashboardService();
        inject(service, "sessions", sessions); inject(service, "dataDir", directory.toString());
        inject(service, "usageAudit", new UsageAuditRepository(directory));
        inject(service, "config", new ContextCacheConfiguration());
        Map<String, Object> result = service.overview("alice", a.getSessionId(), 2);
        assertEquals(2, ((java.util.List<?>) result.get("observations")).size());
        assertFalse(org.noear.snack4.ONode.serialize(result).contains("PRIVATE_BODY"));
        assertTrue(((java.util.List<?>) service.overview("alice", b.getSessionId(), 2).get("observations")).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.overview("bob", a.getSessionId(), 2));
        assertThrows(IllegalArgumentException.class, () -> service.overview("../alice", a.getSessionId(), 2));
    }

    private static void inject(Object instance, String name, Object value) throws Exception {
        java.lang.reflect.Field field = instance.getClass().getDeclaredField(name); field.setAccessible(true); field.set(instance, value);
    }
}
