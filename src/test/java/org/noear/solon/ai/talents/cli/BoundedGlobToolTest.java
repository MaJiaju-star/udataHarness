package org.noear.solon.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.talents.mount.MountManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BoundedGlobToolTest {
    @TempDir Path workspace;

    private BoundedGlobTool tool() {
        return new BoundedGlobTool(new TerminalTalent(new MountManager(workspace.toString())),
                workspace.toString());
    }

    @Test
    void sortsBeforePagingAndMarksIncompleteResults() throws IOException {
        for (int i = 119; i >= 0; i--) {
            Files.createFile(workspace.resolve(String.format("file-%03d.txt", i)));
        }
        String first = tool().glob("*.txt", ".", null, null, null);
        assertTrue(first.contains("file-000.txt"));
        assertTrue(first.contains("file-099.txt"));
        assertFalse(first.contains("file-100.txt"));
        assertTrue(first.contains("after=file-099.txt, limit=100"));
        String second = tool().glob("*.txt", ".", "file-099.txt", null, null);
        assertTrue(second.contains("file-100.txt"));
        assertTrue(second.contains("file-119.txt"));
        assertTrue(second.contains("[搜索完毕]"));
    }

    @Test
    void searchesNestedFilesAndRejectsInvalidPatternsAndEscapes() throws IOException {
        Files.createDirectories(workspace.resolve("src/main"));
        Files.createFile(workspace.resolve("src/main/App.java"));
        Files.createDirectories(workspace.resolve("node_modules"));
        Files.createFile(workspace.resolve("node_modules/Hidden.java"));
        String result = tool().glob("**/*.java", ".", null, 10, null);
        assertTrue(result.contains("src/main/App.java"));
        assertFalse(result.contains("Hidden.java"));
        assertTrue(tool().glob("[", ".", null, null, null).contains("无效"));
        assertThrows(SecurityException.class, () -> tool().glob("*.java", "..", null, null, null));
    }
}
