package com.udata.harness.controller;

import com.udata.harness.repository.ModelContextAgentSession;
import com.udata.harness.repository.SessionRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Collections;
import static org.junit.jupiter.api.Assertions.*;

class ContextControllerTest {
    @TempDir Path directory;

    @Test void inspectionEndpointsUseSessionOwnershipAndRestoreRanges() throws Exception {
        SessionRepository repository = new SessionRepository(directory);
        String id = repository.create("alice", "Context test", "test").getSessionId();
        ModelContextAgentSession session = (ModelContextAgentSession) repository.getSession("alice", id);
        String artifact = session.contextArtifacts().put("read", Collections.singletonMap("path", "A.java"), "class A {}");
        ContextController controller = new ContextController();
        java.lang.reflect.Field field = ContextController.class.getDeclaredField("sessions");
        field.setAccessible(true); field.set(controller, repository);
        assertNotNull(controller.report("alice", id));
        assertNotNull(controller.search("alice", id, "A.java", "read"));
        assertNotNull(controller.restore("alice", id, artifact, 0, 5));
        assertThrows(IllegalArgumentException.class, () -> controller.report("bob", id));
        assertThrows(IllegalArgumentException.class, () -> controller.search("bob", id, "A.java", "read"));
        assertThrows(IllegalArgumentException.class, () -> controller.restore("bob", id, artifact, 0, 5));
        assertThrows(IllegalArgumentException.class, () -> controller.restore("alice", id, "../escape", 0, 5));
    }
}
