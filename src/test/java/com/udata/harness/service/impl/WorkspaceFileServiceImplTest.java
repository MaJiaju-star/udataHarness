package com.udata.harness.service.impl;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.core.handle.DownloadedFile;
import org.noear.solon.core.handle.UploadedFile;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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

    @Test
    void includesDotFilesAndAllInternalDirectories() throws Exception {
        UserWorkspaceServiceImpl workspaces = new UserWorkspaceServiceImpl(tempDir);
        WorkspaceFileServiceImpl files = new WorkspaceFileServiceImpl(workspaces);
        Path workspace = workspaces.getOrCreate("alice");
        Files.createDirectories(workspace.resolve(".opencode"));
        Files.writeString(workspace.resolve(".opencode/config.json"), "{}");
        Files.writeString(workspace.resolve(".env.example"), "TOKEN=");
        Files.createDirectories(workspace.resolve(".git"));

        List<Map<String, Object>> tree = files.tree("alice", "", 3);
        List<String> names = tree.stream().map(item -> String.valueOf(item.get("name"))).toList();

        assertTrue(names.contains(".opencode"));
        assertTrue(names.contains(".env.example"));
        assertTrue(names.contains(".git"));
        assertEquals(1, files.search("alice", ".env").size());
    }

    @Test
    void uploadsAndDownloadsBinaryFiles() throws Exception {
        WorkspaceFileServiceImpl files =
                new WorkspaceFileServiceImpl(new UserWorkspaceServiceImpl(tempDir));
        byte[] content = new byte[]{0, 1, 2, 3, 127};
        UploadedFile upload = new UploadedFile(
                "application/octet-stream", content.length,
                new ByteArrayInputStream(content), "sample.bin", "bin");

        Map<String, Object> saved = files.upload("alice", "artifacts", upload);
        DownloadedFile download = files.download("alice", "artifacts/sample.bin");

        assertEquals("artifacts/sample.bin", saved.get("path"));
        assertArrayEquals(content, download.getContent().readAllBytes());
        download.close();
    }

    @Test
    void recursivelyDeletesDirectoriesAndProtectsWorkspaceRoot() {
        WorkspaceFileServiceImpl files =
                new WorkspaceFileServiceImpl(new UserWorkspaceServiceImpl(tempDir));
        files.save("alice", "generated/nested/result.txt", "done");

        files.delete("alice", "generated");

        assertThrows(IllegalArgumentException.class,
                () -> files.read("alice", "generated/nested/result.txt"));
        assertThrows(IllegalArgumentException.class, () -> files.delete("alice", ""));
    }
}
