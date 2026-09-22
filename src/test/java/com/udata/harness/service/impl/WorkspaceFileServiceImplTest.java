package com.udata.harness.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspaceFileServiceImplTest {
    @TempDir
    Path tempDir;

    @Test
    void isolatesFilesByUserAndRejectsTraversal() {
        WorkspaceFileServiceImpl files =
                new WorkspaceFileServiceImpl(new UserWorkspaceServiceImpl(tempDir));

        files.save("alice", "notes/task.txt", "alice-only");
        Map<String, Object> aliceFile = files.read("alice", "notes/task.txt");

        assertEquals("alice-only", aliceFile.get("content"));
        assertThrows(IllegalArgumentException.class, () -> files.read("bob", "notes/task.txt"));
        assertThrows(IllegalArgumentException.class, () -> files.save("alice", "../escape.txt", "bad"));
    }
}
