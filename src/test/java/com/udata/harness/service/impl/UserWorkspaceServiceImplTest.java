package com.udata.harness.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

class UserWorkspaceServiceImplTest {
    @TempDir
    Path tempDir;

    @Test
    void createsIsolatedDirectoriesAndRejectsTraversal() {
        UserWorkspaceServiceImpl service = new UserWorkspaceServiceImpl(tempDir);
        Path alice = service.getOrCreate("alice");
        Path bob = service.getOrCreate("bob");

        assertNotEquals(alice, bob);
        assertTrue(alice.startsWith(tempDir));
        assertThrows(IllegalArgumentException.class, () -> service.getOrCreate("../escape"));
    }

    @Test
    void registersAndActivatesAllowedLocalDirectory() throws Exception {
        Path allowed = Files.createDirectories(tempDir.resolve("projects"));
        Path project = Files.createDirectories(allowed.resolve("demo"));
        UserWorkspaceServiceImpl service = new UserWorkspaceServiceImpl(
                tempDir.resolve("defaults"), tempDir.resolve("data"), allowed.toString());

        assertEquals(1, service.roots().size());
        Path listed = Paths.get(String.valueOf(service.children(allowed.toString()).get(0).get("path")));
        assertTrue(Files.isSameFile(project, listed));

        String workspaceId = service.register("alice", project.toString()).getWorkspaceId();

        assertTrue(Files.isSameFile(project, service.getOrCreate("alice")));
        assertEquals(workspaceId, service.getActive("alice").getWorkspaceId());
        assertEquals(2, service.list("alice").size());
    }
}
