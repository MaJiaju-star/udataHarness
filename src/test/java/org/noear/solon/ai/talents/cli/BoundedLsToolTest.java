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
        assertTrue(first.contains("after=1:file-199.txt, limit=200"));

        String second = tool().ls(".", false, false, "1:file-199.txt", null, null);
        assertTrue(second.contains("file-200.txt"));
        assertTrue(second.contains("file-219.txt"));
        assertTrue(second.contains("[目录列举完毕]"));
    }

    @Test
    void filtersHiddenAndRecursesPastThreeLevels() throws IOException {
        Files.createFile(workspace.resolve(".hidden"));
        Path nested = Files.createDirectories(workspace.resolve("a/b/c"));
        Files.createFile(nested.resolve("deep.txt"));
        String flat = tool().ls(".", false, false, null, null, null);
        assertFalse(flat.contains(".hidden"));
        assertTrue(flat.contains("a/ [浅层"));
        assertFalse(flat.contains("deep.txt"));
        String recursive = tool().ls(".", true, false, null, null, null);
        assertTrue(recursive.contains("[DIR] a/b/c/"));
        assertTrue(recursive.contains("[FILE] a/b/c/deep.txt"));
        assertTrue(recursive.contains("[目录列举完毕]"));
        assertTrue(tool().ls(".", false, true, null, null, null).contains(".hidden"));
    }

    @Test
    void adaptiveModeExpandsSmallSiblingsAndSummarizesLargeOne() throws IOException {
        Path large = Files.createDirectories(workspace.resolve("a-large"));
        for (int i = 0; i < 20; i++) Files.createFile(large.resolve("item-" + i + ".java"));
        Path small = Files.createDirectories(workspace.resolve("z-small/nested"));
        Files.createFile(small.resolve("Target.java"));
        String result = tool().ls(".", false, false, null, null, null);
        assertTrue(result.contains("├── a-large/ [已收起：浅层至少 13 项"));
        assertTrue(result.contains("└── z-small/ [浅层"));
        assertTrue(result.contains("    └── nested/\n        └── Target.java"));
        assertTrue(result.contains("样例："));
        assertFalse(result.contains("a-large/item-19.java"));
        assertTrue(tool().ls(".", false, false, "old-cursor", null, null)
                .contains("auto 模式的 after 游标"));
        String flat = tool().ls(".", false, false, null, null, "flat", null);
        assertTrue(flat.contains("[DIR] z-small/"));
        assertFalse(flat.contains("Target.java"));
    }

    @Test
    void adaptivePagesDirectoriesBeforeFlatFilesWithoutLosingCoverage() throws IOException {
        for (int i = 0; i < 205; i++) {
            Files.createDirectories(workspace.resolve(String.format("dir-%03d", i)));
        }
        Files.createFile(workspace.resolve("a-file.txt"));
        String first = tool().ls(".", false, false, null, null, null);
        assertTrue(first.contains("dir-199/"));
        assertFalse(first.contains("dir-200/"));
        assertFalse(first.contains("a-file.txt"));
        assertTrue(first.contains("after=0:dir-199, limit=200"));
        String second = tool().ls(".", false, false, "0:dir-199", null, null);
        assertTrue(second.contains("dir-200/"));
        assertTrue(second.contains("a-file.txt"));
    }

    @Test
    void preservesSandboxBoundary() {
        assertThrows(SecurityException.class,
                () -> tool().ls("..", false, false, null, null, null));
    }

    @Test
    void recursivelyListsTheJavaPackagePathsFromTheReportedCase() throws IOException {
        Path application = Files.createDirectories(workspace.resolve("src/main/java/com/udata/harness/service/impl"));
        Path tools = Files.createDirectories(workspace.resolve("src/main/java/org/noear/solon/ai/talents/cli"));
        Files.createFile(application.resolve("ExampleService.java"));
        Files.createFile(tools.resolve("ExampleTool.java"));
        String result = tool().ls("src/main/java", true, false, null, null, null);
        assertTrue(result.contains("[FILE] src/main/java/com/udata/harness/service/impl/ExampleService.java"));
        assertTrue(result.contains("[FILE] src/main/java/org/noear/solon/ai/talents/cli/ExampleTool.java"));
        assertTrue(result.contains("不限深度"));
    }

    @Test
    void explicitDepthMarksUnexpandedDirectoriesAndDoesNotClaimCompletion() throws IOException {
        Files.createDirectories(workspace.resolve("a/b/c/d"));
        Files.createFile(workspace.resolve("a/b/c/d/deep.txt"));
        String result = tool().ls(".", true, false, null, null, null, 3, null);
        assertTrue(result.contains("[DIR] a/b/c/ [达到 max_depth，未展开]"));
        assertFalse(result.contains("deep.txt"));
        assertFalse(result.contains("[目录列举完毕]"));
        assertTrue(result.contains("after 仅续接本深度范围内"));
        assertTrue(tool().ls(".", true, false, null, 1, null, 3, null).contains("recursive=true, max_depth=3"));
    }

    @Test
    void recursivePaginationKeepsDeepEntriesReachableWithoutDuplicates() throws IOException {
        Path nested = Files.createDirectories(workspace.resolve("a/b/c/d"));
        for (int i = 0; i < 10; i++) Files.createFile(nested.resolve("file-" + i + ".txt"));
        java.util.Set<String> entries = new java.util.HashSet<>();
        String cursor = null;
        int pages = 0;
        while (true) {
            String result = tool().ls(".", true, false, cursor, 3, null);
            pages++;
            assertTrue(pages < 10);
            for (String line : result.split("\n")) if (line.startsWith("[DIR]") || line.startsWith("[FILE]")) assertTrue(entries.add(line));
            java.util.regex.Matcher next = java.util.regex.Pattern.compile("after=([^,\\n]+)").matcher(result);
            if (!next.find()) { assertTrue(result.contains("[目录列举完毕]")); break; }
            assertTrue(result.contains("recursive=true")); cursor = next.group(1);
        }
        assertTrue(entries.contains("[FILE] a/b/c/d/file-9.txt"));
        org.junit.jupiter.api.Assertions.assertEquals(14, entries.size());
    }

    @Test
    void validatesDepthAndPublishesTheOptionalParameterInTheToolSchema() throws IOException {
        assertTrue(tool().ls(".", false, false, null, null, null, 3, null).contains("max_depth 仅用于"));
        assertTrue(tool().ls(".", true, false, null, null, null, 0, null).contains("1～64"));
        assertTrue(tool().ls(".", true, false, null, null, null, 65, null).contains("1～64"));
        assertTrue(tool().getTools(org.noear.solon.ai.chat.prompt.Prompt.of(java.util.Collections.emptyList())).stream()
                .anyMatch(item -> "ls".equals(item.name()) && item.inputSchema().contains("max_depth")));
    }

    @Test
    void interruptedScanNeverReturnsAMisleadingCompletionOrContinuation() throws IOException {
        try {
            Thread.currentThread().interrupt();
            String result = tool().ls(".", true, false, null, null, null);
            assertTrue(result.contains("扫描已取消"));
            assertFalse(result.contains("[目录列举完毕]"));
            assertFalse(result.contains("after="));
        } finally { Thread.interrupted(); }
    }

    @Test
    void modelFacingToolActuallyAppliesMaxDepthWithoutExposingCwd() throws IOException {
        Files.createDirectories(workspace.resolve("nested/deeper"));
        Files.createFile(workspace.resolve("nested/deeper/Deep.java"));
        org.noear.solon.ai.chat.tool.FunctionTool ls = tool().getTools(
                org.noear.solon.ai.chat.prompt.Prompt.of(java.util.Collections.emptyList())).stream()
                .filter(item -> "ls".equals(item.name())).findFirst().get();
        java.util.Map<String, Object> args = new java.util.LinkedHashMap<>();
        args.put("path", "."); args.put("recursive", true); args.put("max_depth", 1);
        org.noear.solon.ai.chat.tool.ToolResult result = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> ls.call(args));
        assertTrue(result.getContent().contains("达到 max_depth，未展开"));
        assertFalse(result.getContent().contains("Deep.java"));
        assertFalse(ls.inputSchema().contains("__cwd"));
    }
}
