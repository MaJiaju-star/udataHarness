package org.noear.solon.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.talents.mount.MountManager;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.lang.reflect.Method;
import java.util.Arrays;

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
    void searchVisitorsDoNotDependOnJava8SyntheticAccessors() {
        for (Class<?> type : Arrays.asList(BoundedGlobTool.class, BoundedGrepTool.class,
                BoundedLsTool.class)) {
            assertFalse(Arrays.stream(type.getDeclaredMethods()).map(Method::getName)
                    .anyMatch(name -> name.startsWith("access$")), type.getSimpleName());
        }
    }

    @Test
    void sortsBeforePagingAndMarksIncompleteResults() throws IOException {
        for (int i = 219; i >= 0; i--) {
            Files.createFile(workspace.resolve(String.format("file-%03d.txt", i)));
        }
        String first = tool().glob("*.txt", ".", null, null, null);
        assertTrue(first.contains("file-000.txt"));
        assertTrue(first.contains("file-199.txt"));
        assertFalse(first.contains("file-200.txt"));
        assertTrue(first.contains("after=file-199.txt, limit=200"));
        String second = tool().glob("*.txt", ".", "file-199.txt", null, null);
        assertTrue(second.contains("file-200.txt"));
        assertTrue(second.contains("file-219.txt"));
        assertTrue(second.contains("[搜索完毕]"));
    }

    @Test
    void searchesNestedFilesAndRejectsInvalidPatternsAndEscapes() throws IOException {
        Files.createDirectories(workspace.resolve("src/main"));
        Files.createFile(workspace.resolve("src/main/App.java"));
        Files.createDirectories(workspace.resolve("src/main/java/org/noear/solon/ai/talents/cli"));
        Files.createFile(workspace.resolve("src/main/java/org/noear/solon/ai/talents/cli/BoundedGlobTool.java"));
        Files.createDirectories(workspace.resolve("node_modules"));
        Files.createFile(workspace.resolve("node_modules/Hidden.java"));
        String result = tool().glob("**/*.java", ".", null, 10, null);
        assertTrue(result.contains("src/main/App.java"));
        assertTrue(tool().glob("src/main/java/org/noear/solon/ai/talents/cli/*.java", ".", null, null, null)
                .contains("BoundedGlobTool.java"));
        assertFalse(result.contains("Hidden.java"));
        assertTrue(tool().glob("[", ".", null, null, null).contains("无效"));
        assertThrows(SecurityException.class, () -> tool().glob("*.java", "..", null, null, null));
    }
}
