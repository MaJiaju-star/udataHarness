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

class BoundedLsToolTest {
    @TempDir Path workspace;

    private BoundedLsTool tool() {
        return new BoundedLsTool(new TerminalTalent(new MountManager(workspace.toString())),
                workspace.toString());
    }

    @Test
    void pagesLargeDirectoryWithoutLosingEntries() throws IOException {
        for (int i = 0; i < 220; i++) {
            Files.createFile(workspace.resolve(String.format("file-%03d.txt", i)));
        }
        String first = tool().ls(".", false, false, null, null, null);
        assertTrue(first.contains("file-199.txt"));
        assertFalse(first.contains("file-200.txt"));
        assertTrue(first.contains("after=file-199.txt, limit=200"));

        String second = tool().ls(".", false, false, "file-199.txt", null, null);
        assertTrue(second.contains("file-200.txt"));
        assertTrue(second.contains("file-219.txt"));
        assertTrue(second.contains("[目录列举完毕]"));
    }

    @Test
    void filtersHiddenAndRespectsRecursiveDepth() throws IOException {
        Files.createFile(workspace.resolve(".hidden"));
        Path nested = Files.createDirectories(workspace.resolve("a/b/c"));
        Files.createFile(nested.resolve("deep.txt"));
        String flat = tool().ls(".", false, false, null, null, null);
        assertFalse(flat.contains(".hidden"));
        assertTrue(flat.contains("[DIR] a/"));
        assertFalse(flat.contains("deep.txt"));
        String recursive = tool().ls(".", true, false, null, null, null);
        assertTrue(recursive.contains("[DIR] a/b/c/"));
        assertFalse(recursive.contains("deep.txt"));
        assertTrue(tool().ls(".", false, true, null, null, null).contains(".hidden"));
    }

    @Test
    void preservesSandboxBoundary() {
        assertThrows(SecurityException.class,
                () -> tool().ls("..", false, false, null, null, null));
    }
}
