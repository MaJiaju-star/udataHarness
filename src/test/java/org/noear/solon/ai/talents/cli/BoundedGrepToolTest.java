package org.noear.solon.ai.talents.cli;

import com.udata.harness.common.context.ContextArtifactStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.talents.mount.MountManager;
import org.noear.solon.ai.chat.ModelOptionsAmend;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.TalentUtil;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

class BoundedGrepToolTest {
    @TempDir Path workspace;

    private BoundedGrepTool tool() {
        return new BoundedGrepTool(new TerminalTalent(new MountManager(workspace.toString())),
                workspace.toString());
    }

    @Test
    void searchesOnlyCurrentSessionToolResults() throws IOException {
        ContextArtifactStore store = new ContextArtifactStore(workspace.resolve("artifacts"));
        String own = store.save("session-a", "own", "bash", "needle-own\n", "large", 5).id;
        store.save("session-b", "foreign", "bash", "needle-foreign\n", "large", 5);
        BoundedGrepTool grep = new BoundedGrepTool(
                new TerminalTalent(new MountManager(workspace.toString())), workspace.toString(), store);
        String output = grep.grep("needle", "@tool-results", null, "content", null,
                null, null, true, null, "session-a");
        assertTrue(output.contains("@tool-results/" + own + ".txt:1: needle-own"));
        assertFalse(output.contains("needle-foreign"));
        assertTrue(grep.grep("needle", "@tool-results/../secret.txt", null, null, null,
                null, null, null, null, "session-a").contains("路径无效"));
    }

    @Test
    void providesThreeOutputModesAndStableContentPaging() throws IOException {
        Files.write(workspace.resolve("a.txt"), "hit one\nmiss\nhit two\n".getBytes(StandardCharsets.UTF_8));
        Files.write(workspace.resolve("b.txt"), "hit three\n".getBytes(StandardCharsets.UTF_8));
        String files = tool().grep("hit", ".", "*.txt", "files_with_matches", null, null, null, null, null);
        assertTrue(files.contains("a.txt"));
        assertTrue(files.contains("b.txt"));
        assertFalse(files.contains("hit one"));

        String counts = tool().grep("hit", ".", "*.txt", "count", null, null, null, null, null);
        assertTrue(counts.contains("a.txt: 2 matching lines"));
        assertTrue(counts.contains("b.txt: 1 matching lines"));

        String first = tool().grep("hit", ".", "*.txt", "content", null, null, 2, null, null);
        assertTrue(first.contains("a.txt:1: hit one"));
        assertTrue(first.contains("a.txt:3: hit two"));
        assertFalse(first.contains("hit three"));
        assertTrue(first.contains("after=a.txt#0000000003, limit=2"));
        String second = tool().grep("hit", ".", "*.txt", "content", null, "a.txt#0000000003", 2, null, null);
        assertTrue(second.contains("b.txt:1: hit three"));
        assertTrue(second.contains("[搜索完毕]"));
    }

    @Test
    void defaultSummaryShowsCoverageAndUsefulLinesWithoutDumpingEveryMatch() throws IOException {
        Files.write(workspace.resolve("a.java"),
                "needle one\nneedle two\nneedle three\n".getBytes(StandardCharsets.UTF_8));
        Files.write(workspace.resolve("b.java"), "needle four\n".getBytes(StandardCharsets.UTF_8));
        String summary = tool().grep("needle", ".", "*.java", null, null,
                null, null, true, null);
        assertTrue(summary.contains("命中文件 2 个，命中行 4 处"));
        assertTrue(summary.contains("a.java:1: needle one"));
        assertTrue(summary.contains("a.java:2: needle two"));
        assertFalse(summary.contains("needle three"));
        assertTrue(summary.contains("[搜索扫描完毕]"));
        assertTrue(tool().grep("needle", ".", null, "summary", null,
                "a.java", null, true, null).contains("没有 after 游标"));
    }

    @Test
    void literalSearchAndInvalidRegexAreExplicit() throws IOException {
        Files.write(workspace.resolve("plain.txt"), "[tag]\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(tool().grep("[", ".", null, null, null, null, null, null, null).contains("无效正则"));
        assertTrue(tool().grep("[tag]", ".", null, null, null, null, null, true, null)
                .contains("plain.txt:1: [tag]"));
    }

    @Test
    void contentModeIncludesBoundedSurroundingLines() throws IOException {
        Files.write(workspace.resolve("context.txt"),
                "before\nhit\nafter\n".getBytes(StandardCharsets.UTF_8));
        String result = tool().grep("hit", ".", null, "content", 1,
                null, null, null, null);
        assertTrue(result.contains("  -1 | before"));
        assertTrue(result.contains("context.txt:2: hit"));
        assertTrue(result.contains("  +1 | after"));
    }

    @Test
    void giantLineDoesNotExpandMemoryAndIsReportedAsIncomplete() throws IOException {
        char[] large = new char[200_000];
        java.util.Arrays.fill(large, 'x');
        Files.write(workspace.resolve("large.txt"), (new String(large) + "\nhit\n").getBytes(StandardCharsets.UTF_8));
        String result = tool().grep("hit", ".", null, "content", null, null, null, null, null);
        assertTrue(result.contains("large.txt:2: hit"));
        assertTrue(result.contains("搜索不完整"));
        assertTrue(result.contains("跳过超长行 1"));
        String summary = tool().grep("hit", ".", null, null, null, null, null, null, null);
        assertTrue(summary.contains("命中文件 1 个，命中行 1 处"));
        assertTrue(summary.contains("[搜索不完整]"));
        assertTrue(summary.contains("跳过超长行 1"));
        assertThrows(SecurityException.class,
                () -> tool().grep("hit", "..", null, null, null, null, null, null, null));
    }

    @Test
    void characterBudgetReturnsAUsableContinuation() throws IOException {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 60; i++) {
            body.append("hit ").append(repeat('x', 700)).append('\n');
        }
        Files.write(workspace.resolve("many.txt"), body.toString().getBytes(StandardCharsets.UTF_8));
        String first = tool().grep("hit", ".", null, "content", null,
                null, 100, null, null);
        assertTrue(first.contains("[内容未完]"));
        assertTrue(first.length() > 20_000);
        assertTrue(first.length() < 33_000);
        String cursor = first.substring(first.indexOf("after=") + 6, first.indexOf(", limit="));
        int lastLine = Integer.parseInt(cursor.substring(cursor.lastIndexOf('#') + 1));
        String second = tool().grep("hit", ".", null, "content", null,
                cursor, 100, null, null);
        assertTrue(second.contains("many.txt:" + (lastLine + 1) + ":"));
    }

    @Test
    void laterTalentsReplaceGrantedSolonSearchTools() {
        TerminalTalent terminal = new TerminalTalent(new MountManager(workspace.toString()));
        TerminalTalentProxy builtIn = new TerminalTalentProxy(terminal);
        builtIn.addTools("glob", "grep");
        BoundedGlobTool glob = new BoundedGlobTool(terminal, workspace.toString());
        BoundedGrepTool grep = new BoundedGrepTool(terminal, workspace.toString());
        ModelOptionsAmend<?, ?> options = new ModelOptionsAmend<>();
        options.talentAdd(builtIn);
        options.talentAdd(glob);
        options.talentAdd(grep);
        TalentUtil.activeTalents(options, Prompt.of("test"), new StringBuilder());
        assertSame(glob.getToolAry("glob").iterator().next(), options.tools().stream()
                .filter(tool -> "glob".equals(tool.name())).findFirst().orElseThrow(AssertionError::new));
        assertSame(grep.getToolAry("grep").iterator().next(), options.tools().stream()
                .filter(tool -> "grep".equals(tool.name())).findFirst().orElseThrow(AssertionError::new));
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }
}
