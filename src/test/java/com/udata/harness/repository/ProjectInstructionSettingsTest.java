package com.udata.harness.repository;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class ProjectInstructionSettingsTest {
    @TempDir Path directory;
    @Test void persistsAcrossInstancesAndIsolatesUsersAndWorkspaces() {
        ProjectInstructionSettings settings = new ProjectInstructionSettings(directory);
        assertEquals("auto", settings.mode("alice", "ws-1"));
        settings.save("alice", "ws-1", "agents");
        settings.save("alice", "ws-2", "off");
        settings.save("bob", "ws-1", "claude");
        ProjectInstructionSettings reopened = new ProjectInstructionSettings(directory);
        assertEquals("agents", reopened.mode("alice", "ws-1"));
        assertEquals("off", reopened.mode("alice", "ws-2"));
        assertEquals("claude", reopened.mode("bob", "ws-1"));
        assertEquals("auto", reopened.mode("bob", "ws-2"));
    }
    @Test void rejectsInvalidModesWithoutChangingExistingPreference() {
        ProjectInstructionSettings settings = new ProjectInstructionSettings(directory);
        settings.save("alice", "ws", "agents");
        assertThrows(IllegalArgumentException.class, () -> settings.save("alice", "ws", "unknown"));
        assertEquals("agents", settings.mode("alice", "ws"));
        assertThrows(IllegalArgumentException.class, () -> settings.save("../outside", "ws", "off"));
    }

    @Test void controllerUsesOwnedSessionsWorkspaceAndRejectsAnotherUser() throws Exception {
        SessionRepository sessions = new SessionRepository(directory.resolve("sessions"));
        com.udata.harness.service.impl.UserWorkspaceServiceImpl workspaces = new com.udata.harness.service.impl.UserWorkspaceServiceImpl(
                directory.resolve("workspace"), directory.resolve("data"), directory.toString());
        workspaces.getActive("alice");
        com.udata.harness.common.domain.SessionMetadata session = sessions.create("alice", "mode", "stub");
        ProjectInstructionSettings settings = new ProjectInstructionSettings(directory);
        com.udata.harness.controller.SessionController controller = new com.udata.harness.controller.SessionController();
        inject(controller, "sessionRepository", sessions); inject(controller, "projectInstructionSettings", settings);
        inject(controller, "instructionWorkspaces", workspaces);
        controller.projectInstructionMode("alice", session.getSessionId(), "claude");
        assertEquals("claude", settings.mode("alice", "default"));
        assertThrows(IllegalArgumentException.class, () -> controller.projectInstructionMode("bob", session.getSessionId(), "off"));
        assertEquals("claude", settings.mode("alice", "default"));
    }
    private static void inject(Object target, String key, Object value) throws Exception {
        java.lang.reflect.Field field = target.getClass().getDeclaredField(key); field.setAccessible(true); field.set(target, value);
    }
}
