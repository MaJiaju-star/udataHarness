package com.udata.harness.service.impl;

import com.udata.harness.common.request.CapabilityRequest;
import com.udata.harness.service.UserHarnessEngineService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.mcp.client.McpServerParameters;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CapabilityServiceImplTest {
    @TempDir
    Path tempDir;

    @Test
    void validatesCapabilityNames() {
        assertEquals("code-review", CapabilityServiceImpl.requireName("Code-Review"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CapabilityServiceImpl.requireName("../escape"));
        assertThrows(
                IllegalArgumentException.class,
                () -> CapabilityServiceImpl.requireName("has spaces"));
    }

    @Test
    void activatesWholeSkillDirectoryForOnlySelectedUser() throws Exception {
        UserHarnessEngineService engines = new UserHarnessEngineService() {
            @Override
            public HarnessEngine get(String userId) {
                throw new UnsupportedOperationException();
            }

            @Override
            public java.util.Collection<HarnessEngine> all() {
                return List.of();
            }

            @Override
            public void refreshUserCapabilities(String userId) {
            }

            @Override
            public boolean isSkillLoaded(String userId, String skillName) {
                return false;
            }

            @Override
            public void refreshAgentsForAll() {
            }

            @Override
            public void putMcp(String name, McpServerParameters parameters) {
            }

            @Override
            public void removeMcp(String name) {
            }

        };
        Path workspace = tempDir.resolve("workspace");
        Path data = tempDir.resolve("data");
        CapabilityServiceImpl service = new CapabilityServiceImpl(
                engines, new UserWorkspaceServiceImpl(workspace), data);
        CapabilityRequest request = new CapabilityRequest();
        request.setName("review");
        request.setContent("---\ndescription: Review code\n---\n# Review");
        service.saveSkill(request);
        Path library = data.resolve("skill-library/review");
        Files.createDirectories(library.resolve("scripts"));
        Files.writeString(library.resolve("scripts/check.js"), "console.log('ok')");

        service.activateSkill("alice", "review");

        assertTrue(Files.exists(workspace.resolve("alice/.soloncode/skills/review/SKILL.md")));
        assertTrue(Files.exists(workspace.resolve("alice/.soloncode/skills/review/scripts/check.js")));
        assertFalse(Files.exists(workspace.resolve("bob/.soloncode/skills/review")));
        List<Map<String, Object>> alice = service.listSkills("alice");
        assertEquals(true, alice.get(0).get("active"));

        service.deactivateSkill("alice", "review");
        assertFalse(Files.exists(workspace.resolve("alice/.soloncode/skills/review")));
    }
}
