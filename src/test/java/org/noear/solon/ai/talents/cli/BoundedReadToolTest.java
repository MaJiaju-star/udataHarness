package org.noear.solon.ai.talents.cli;

import com.udata.harness.common.context.ContextArtifactStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.talents.mount.MountManager;
import org.noear.solon.ai.chat.ModelOptionsAmend;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.TalentUtil;
import org.noear.solon.ai.chat.tool.FunctionTool;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

class BoundedReadToolTest {
    @TempDir Path workspace;

    private BoundedReadTool tool() {
        return new BoundedReadTool(new TerminalTalent(new MountManager(workspace.toString())),
                workspace.toString());
    }

    @Test
    void unchangedRangeReturnsOnlyRecoverableReferenceAndChangeReadsAgain() throws IOException {
        ContextArtifactStore store = new ContextArtifactStore(workspace.resolve("artifacts"));
        BoundedReadTool reader = new BoundedReadTool(
                new TerminalTalent(new MountManager(workspace.toString())), workspace.toString(), store);
        Path source = workspace.resolve("source.java");
        StringBuilder content = new StringBuilder();
        for (int line = 1; line <= 3500; line++) content.append("class line").append(line).append(" {}\n");
        Files.write(source, content.toString().getBytes(StandardCharsets.UTF_8));

        String first = reader.read("source.java", null, null, null, "session-a");
        assertTrue(first.contains("3500 | class line3500"));
        assertTrue(first.contains("[文件读取完毕]"));
        String second = reader.read("source.java", null, null, null, "session-a");
        assertTrue(second.startsWith("<read-unchanged>"));
        assertTrue(second.length() < 600);
        assertTrue(second.contains("@tool-results/"));
        String id = second.substring(second.indexOf("@tool-results/") + 14,
                second.indexOf(".txt", second.indexOf("@tool-results/")));
        assertTrue(store.restore("session-a", id, 0, first.length()).endsWith(first));

        assertTrue(reader.read("source.java", 2, 10, null, "session-a").contains("2 | class line2"));
        assertTrue(reader.read("source.java", null, null, null, "session-b").contains("3500 | class line3500"));
        Files.write(source, (content.toString() + "changed\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(reader.read("source.java", null, null, null, "session-a").contains("changed"));
    }

    @Test
    void readsOnlyCommittedResultsFromInjectedSession() throws IOException {
        ContextArtifactStore store = new ContextArtifactStore(workspace.resolve("artifacts"));
        String id = store.save("session-a", "call-a", "bash", "first\nsecond\n", "large", 5).id;
        BoundedReadTool reader = new BoundedReadTool(
                new TerminalTalent(new MountManager(workspace.toString())), workspace.toString(), store);
        String name = "@tool-results/" + id + ".txt";
        assertTrue(reader.read(name, 2, 1, null, "session-a").contains("2 | second"));
        assertTrue(reader.read(name, 1, 1, null, "session-a").contains("历史工具结果快照"));
        assertTrue(reader.read(name, 1, 1, null, "session-b").contains("找不到"));
        assertTrue(reader.read("@tool-results/../" + id + ".txt", 1, 1, null, "session-a")
                .contains("路径无效"));
        assertTrue(reader.read(name, 1, 1, null, null).contains("找不到"));
        String giant = repeat('x', 20_000) + "THE_END";
        String giantId = store.save("session-a", "call-giant", "bash", giant, "large", 5).id;
        String tail = reader.read("@tool-results/" + giantId + ".txt", null, null,
                20_000, 100, null, "session-a");
        assertTrue(tail.contains("THE_END"));
        assertTrue(tail.contains("has_more=false"));
        String largerPage = reader.read("@tool-results/" + giantId + ".txt", null, null,
                0, null, null, "session-a");
        assertTrue(largerPage.contains("next_offset=12000"));
        assertTrue(largerPage.length() > 12000);
        String linePage = reader.read("@tool-results/" + giantId + ".txt", 1, 1, null, "session-a");
        assertTrue(linePage.contains("单行已截断"));
        assertTrue(linePage.contains("offset=2"));
    }

    @Test
    void defaultsToFourThousandLinesAndProvidesContinuation() throws IOException {
        StringBuilder contents = new StringBuilder();
        for (int i = 1; i <= 4002; i++) contents.append("line-").append(i).append('\n');
        Files.write(workspace.resolve("lines.txt"), contents.toString().getBytes(StandardCharsets.UTF_8));

        String first = tool().read("lines.txt", null, null, null);
        assertTrue(first.contains("  4000 | line-4000"));
        assertFalse(first.contains("line-4001"));
        assertTrue(first.contains("offset=4001, limit=4000"));
        assertTrue(first.contains("文件版本 SHA-256: " + FileVersion.sha256(workspace.resolve("lines.txt"))));
        String second = tool().read("lines.txt", 4001, null, null);
        assertTrue(second.contains("line-4002"));
        assertTrue(second.contains("[文件读取完毕]"));
    }

    @Test
    void unchangedPartialReadKeepsNextOffsetInShortReference() throws IOException {
        ContextArtifactStore store = new ContextArtifactStore(workspace.resolve("artifacts"));
        BoundedReadTool reader = new BoundedReadTool(
                new TerminalTalent(new MountManager(workspace.toString())), workspace.toString(), store);
        StringBuilder content = new StringBuilder();
        for (int line = 1; line <= 4002; line++) content.append("line-").append(line).append('\n');
        Files.write(workspace.resolve("partial.java"), content.toString().getBytes(StandardCharsets.UTF_8));
        assertTrue(reader.read("partial.java", null, null, null, "session-a").contains("offset=4001"));
        assertTrue(reader.read("partial.java", null, null, null, "session-a")
                .contains("继续当前文件请用 offset=4001"));
    }

    @Test
    void giantLineIsBoundedAndNextLineRemainsReadable() throws IOException {
        String large = repeat('x', 2_000_000) + "\nsecond\n";
        Files.write(workspace.resolve("giant.txt"), large.getBytes(StandardCharsets.UTF_8));

        String first = tool().read("giant.txt", 1, 1, null);
        assertTrue(first.length() > 60_000);
        assertTrue(first.length() < 67_000);
        assertTrue(first.contains("单行已截断"));
        assertTrue(first.contains("offset=2"));
        assertTrue(tool().read("giant.txt", 2, 1, null).contains("second"));
        assertTrue(tool().read("@workspace/giant.txt", 1, 1, null).length() < 67_000);
    }

    @Test
    void byteBudgetDoesNotLoseTheNextLine() throws IOException {
        StringBuilder contents = new StringBuilder();
        for (int i = 1; i <= 5000; i++) contents.append(i).append(' ').append(repeat('a', 120)).append('\n');
        Files.write(workspace.resolve("budget.txt"), contents.toString().getBytes(StandardCharsets.UTF_8));

        String page = tool().read("budget.txt", 1, 8000, null);
        assertTrue(page.contains("字节上限"));
        assertTrue(page.contains("   750 |"));
        assertTrue(page.length() > 150_000);
        assertTrue(page.length() < 264_000);
        int next = Integer.parseInt(page.substring(page.indexOf("offset=") + 7, page.indexOf(", limit=")));
        assertTrue(tool().read("budget.txt", next, 1, null).contains(" | " + next + " "));
    }

    @Test
    void preservesSandboxPathChecks() throws IOException {
        Path outside = workspace.getParent().resolve("outside.txt");
        assertThrows(SecurityException.class, () -> tool().read(outside.toString(), 1, 1, null));
        assertThrows(SecurityException.class, () -> tool().read("../outside.txt", 1, 1, null));
    }

    @Test
    void laterTalentOverridesTheBuiltInReadAtActivation() {
        TerminalTalent terminal = new TerminalTalent(new MountManager(workspace.toString()));
        TerminalTalentProxy builtIn = new TerminalTalentProxy(terminal);
        builtIn.addTools("read");
        BoundedReadTool bounded = new BoundedReadTool(terminal, workspace.toString());
        ModelOptionsAmend<?, ?> options = new ModelOptionsAmend<>();
        options.talentAdd(builtIn);
        options.talentAdd(bounded);

        TalentUtil.activeTalents(options, Prompt.of("test"), new StringBuilder());
        FunctionTool selected = options.tools().stream()
                .filter(tool -> "read".equals(tool.name()))
                .findFirst().orElseThrow(AssertionError::new);
        assertSame(bounded.getToolAry("read").iterator().next(), selected);
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        java.util.Arrays.fill(chars, value);
        return new String(chars);
    }
}
