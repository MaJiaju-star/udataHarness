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
        assertTrue(first.contains("file-079.txt"));
        assertFalse(first.contains("file-080.txt"));
        assertFalse(first.contains("[FILE]"));
        assertTrue(first.contains("80/220；继续 after=file-079.txt, limit=80"));
        String second = tool().glob("*.txt", ".", "file-079.txt", null, null);
        assertTrue(second.contains("file-080.txt"));
        assertTrue(second.contains("file-159.txt"));
        assertFalse(second.contains("file-160.txt"));
        assertTrue(second.contains("160/220；继续 after=file-159.txt, limit=80"));
        String third = tool().glob("*.txt", ".", "file-159.txt", null, null);
        assertTrue(third.contains("file-160.txt"));
        assertTrue(third.contains("file-219.txt"));
        assertTrue(third.contains("220/220；搜索完毕"));
        String explicit = tool().glob("*.txt", ".", null, 200, null);
        assertTrue(explicit.contains("file-199.txt"));
        assertFalse(explicit.contains("file-200.txt"));
        assertTrue(explicit.contains("200/220；继续 after=file-199.txt, limit=200"));
    }

    @Test
    void groupsSharedDirectoryPrefixesWithoutLosingReconstructablePaths() throws IOException {
        Files.createDirectories(workspace.resolve("src/main/java/example"));
        Files.createDirectories(workspace.resolve("src/test"));
        Files.createFile(workspace.resolve("src/main/java/example/Alpha.java"));
        Files.createFile(workspace.resolve("src/main/java/example/Beta.java"));
        Files.createDirectories(workspace.resolve("src/main/java/example/service"));
        Files.createFile(workspace.resolve("src/main/java/example/service/UserService.java"));
        Files.createFile(workspace.resolve("src/test/Only.java"));

        String result = tool().glob("**/*.java", ".", null, null, null);
        assertTrue(result.contains("src/main/java/example/\n├── Alpha.java\n├── Beta.java\n└── service/UserService.java\n"), result);
        assertTrue(result.contains("src/test/Only.java\n"), result);
        assertTrue(result.contains("4/4；搜索完毕"), result);
    }

    @Test
    void leavesLastBranchOpenWhenMoreMatchesFollowOnNextPage() throws IOException {
        Files.createDirectories(workspace.resolve("src/main/java/example"));
        Files.createFile(workspace.resolve("src/main/java/example/Alpha.java"));
        Files.createFile(workspace.resolve("src/main/java/example/Beta.java"));
        Files.createFile(workspace.resolve("src/main/java/example/Gamma.java"));

        String first = tool().glob("**/*.java", ".", null, 2, null);
        assertTrue(first.contains("src/main/java/example/\n├── Alpha.java\n├── Beta.java\n"), first);
        assertTrue(first.contains("2/3；继续 after=src/main/java/example/Beta.java, limit=2"), first);
        String second = tool().glob("**/*.java", ".", "src/main/java/example/Beta.java", 2, null);
        assertTrue(second.contains("src/main/java/example/Gamma.java"), second);
        assertTrue(second.contains("3/3；搜索完毕"), second);
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
