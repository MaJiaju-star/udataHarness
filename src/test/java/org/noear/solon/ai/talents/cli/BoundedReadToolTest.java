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
import java.nio.file.attribute.FileTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

        String first = reader.read("source.java", 1, 4000, null, "session-a");
        assertTrue(first.contains("3500 | class line3500"));
        assertTrue(first.contains("[文件读取完毕]"));
        String second = reader.read("source.java", 1, 4000, null, "session-a");
        assertTrue(second.startsWith("<read-unchanged>"));
        assertTrue(second.length() < 600);
        assertTrue(second.contains("@tool-results/"));
        String id = second.substring(second.indexOf("@tool-results/") + 14,
                second.indexOf(".txt", second.indexOf("@tool-results/")));
        assertTrue(store.restore("session-a", id, 0, first.length()).endsWith(first));

        assertTrue(reader.read("source.java", 2, 10, null, "session-a").contains("2 | class line2"));
        assertTrue(reader.read("source.java", 1, 4000, null, "session-b").contains("3500 | class line3500"));
        Files.write(source, (content.toString() + "changed\n").getBytes(StandardCharsets.UTF_8));
        assertTrue(reader.read("source.java", 1, 4000, null, "session-a").contains("changed"));
    }

    @Test
    void readsOnlyCommittedResultsFromInjectedSession() throws IOException {
        ContextArtifactStore store = new ContextArtifactStore(workspace.resolve("artifacts"));
        String id = store.save("session-a", "call-a", "bash", "first\nsecond\n", "large", 5).id;
        BoundedReadTool reader = new BoundedReadTool(
                new TerminalTalent(new MountManager(workspace.toString())), workspace.toString(), store);
        String name = "@tool-results/" + id + ".txt";
        assertEquals("second\n", snapshotBody(reader.read(name, 2, 1, null, "session-a")));
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
        assertEquals(repeat('x', BoundedReadTool.SNAPSHOT_OUTPUT_BYTES), snapshotBody(linePage));
        assertTrue(linePage.contains("char_offset=16384"));
        assertFalse(linePage.contains("单行已截断"));
        assertFalse(linePage.contains("offset=2"));
    }

    @Test
    void cachedReadSnapshotPreservesSourceNumbersAndOriginalContinuationWithoutOuterNumbers() throws IOException {
        ContextArtifactStore store = new ContextArtifactStore(workspace.resolve("artifacts"));
        BoundedReadTool reader = cachedTool(store);
        StringBuilder source = new StringBuilder();
        for (int line = 1; line <= 102; line++) source.append("source-").append(line).append('\n');
        Files.write(workspace.resolve("source.java"), source.toString().getBytes(StandardCharsets.UTF_8));
        String original = reader.read("source.java", 100, 1, null, "s");
        String reference = reader.read("source.java", 100, 1, null, "s");
        String path = "@tool-results/" + referenceId(reference) + ".txt";
        String restored = reader.read(path, 1, 4000, null, "s");

        assertEquals(original, snapshotBody(restored));
        assertTrue(restored.contains("   100 | source-100"));
        assertFalse(restored.contains("     3 |    100 |"));
        assertTrue(snapshotBody(restored).contains("offset=101, limit=1"));
        assertTrue(restored.contains("正文中的原文件行号和原工具续读提示保持原样"));
        assertTrue(restored.contains("不表示原文件已读取完毕"));
        assertFalse(reader.readResult(path, 1, 4000, null, null, null, "s").metas().containsKey("sourceHash"));
        String charPage = reader.read(path, null, null, 0, 16000, null, "s");
        assertTrue(charPage.endsWith(original));
        assertTrue(charPage.contains("当前为快照字符分页"));
        assertFalse(charPage.contains("     3 |    100 |"));
    }

    @Test
    void snapshotLinePagingUsesPhysicalSnapshotLinesAndPreservesCrLfBomAndExistingNumbers() throws IOException {
        ContextArtifactStore store = new ContextArtifactStore(workspace.resolve("artifacts"));
        String content = "\uFEFF   100 | first\r\n   101 | second\rthird\n";
        String id = store.save("s", "numbered", "read", content, "read_projection", 0).id;
        String path = "@tool-results/" + id + ".txt";
        BoundedReadTool reader = cachedTool(store);
        String first = reader.read(path, 1, 1, null, "s");
        assertEquals("\uFEFF   100 | first\r\n", snapshotBody(first));
        assertTrue(first.contains("快照范围：物理行 1-1"));
        assertTrue(first.contains("快照行续读（不是原文件续读）：read(file_path=\"" + path + "\", offset=2, limit=1)"));
        assertEquals("   101 | second\r", snapshotBody(reader.read(path, 2, 1, null, "s")));
        assertEquals("third\n", snapshotBody(reader.read(path, 3, 1, null, "s")));
        assertTrue(reader.read(path, 4, 1, null, "s").contains("快照起始物理行"));
    }

    @Test
    void snapshotByteLimitContinuesInsideLongLineWithoutLosingCharactersOrSplittingCrLf() throws IOException {
        ContextArtifactStore store = new ContextArtifactStore(workspace.resolve("artifacts"));
        String content = repeat('x', BoundedReadTool.SNAPSHOT_OUTPUT_BYTES - 1) + "\r\nTAIL";
        String id = store.save("s", "long-line", "bash", content, "stream_output", 0).id;
        String path = "@tool-results/" + id + ".txt";
        BoundedReadTool reader = cachedTool(store);
        String page = reader.read(path, 1, 4000, null, "s");
        String body = snapshotBody(page);
        assertEquals(repeat('x', 16383), body);
        assertTrue(page.contains("快照字符续读（不是原文件续读）"));
        assertTrue(page.contains("char_offset=16383"));
        String rest = reader.read(path, null, null, 16383, 12000, null, "s");
        assertTrue(rest.contains("快照 UTF-16 字符，不是行号"));
        assertTrue(rest.contains("position_unit=snapshot_utf16_chars"));
        assertTrue(rest.endsWith("\r\nTAIL"));
        assertEquals(content, body + rest.substring(rest.indexOf("has_more=false") + "has_more=false".length() + 1));
    }

    @Test
    void snapshotUtf8BudgetUsesUtf16ContinuationAndPreservesSurrogatePairs() throws IOException {
        ContextArtifactStore store = new ContextArtifactStore(workspace.resolve("artifacts"));
        StringBuilder prefix = new StringBuilder();
        for (int i = 0; i < 4095; i++) prefix.append("😀");
        prefix.append("\r\n");
        String content = prefix + "😀tail";
        String id = store.save("s", "unicode", "bash", content, "stream_output", 0).id;
        String path = "@tool-results/" + id + ".txt";
        BoundedReadTool reader = cachedTool(store);
        String page = reader.read(path, 1, 4000, null, "s");
        assertEquals(prefix.toString(), snapshotBody(page));
        assertTrue(snapshotBody(page).getBytes(StandardCharsets.UTF_8).length <= BoundedReadTool.SNAPSHOT_OUTPUT_BYTES);
        assertTrue(page.contains("char_offset=8192"));
        String rest = reader.read(path, null, null, 8192, 2, null, "s");
        assertTrue(rest.endsWith("😀"));
        assertTrue(rest.contains("snapshot_continue=read(file_path=\"" + path + "\", char_offset=8194, char_limit=2)"));
    }

    @Test
    void emptyAndIncompleteSnapshotsStayClearlyHistorical() throws IOException {
        ContextArtifactStore store = new ContextArtifactStore(workspace.resolve("artifacts"));
        String empty = store.save("s", "empty", "bash", "", "stream_output", 0).id;
        BoundedReadTool reader = cachedTool(store);
        assertEquals("", snapshotBody(reader.read("@tool-results/" + empty + ".txt", 1, 1, null, "s")));
        String id;
        try (ContextArtifactStore.StreamingArtifact stream = store.beginStream("s", "partial", "bash")) {
            stream.append("   100 | partial\n");
            id = stream.finish(false).id;
        }
        String restored = reader.read("@tool-results/" + id + ".txt", 1, 1, null, "s");
        assertEquals("   100 | partial\n", snapshotBody(restored));
        assertTrue(restored.contains("原命令捕获不完整"));
    }

    /** 提取快照回读的原样正文，分页元信息与正文分开断言。 */
    private static String snapshotBody(String output) {
        String start = "<snapshot-content>\n";
        int begin = output.indexOf(start);
        assertTrue(begin >= 0, output);
        return output.substring(begin + start.length(), output.lastIndexOf("\n</snapshot-content>"));
    }

    @Test
    void largeFileDefaultsToPreviewAndProvidesContinuation() throws IOException {
        StringBuilder contents = new StringBuilder();
        for (int i = 1; i <= 4002; i++) contents.append("line-").append(i).append('\n');
        Files.write(workspace.resolve("lines.txt"), contents.toString().getBytes(StandardCharsets.UTF_8));

        String first = tool().read("lines.txt", null, null, null);
        assertTrue(first.contains("大文件预览"));
        assertTrue(first.contains("总行数 4002"));
        assertTrue(first.contains("   400 | line-400"));
        assertFalse(first.contains("line-401"));
        assertTrue(first.contains("offset=401, limit=400"));
        assertFalse(first.contains("SHA-256"));
        String complete = tool().read("lines.txt", 1, 4000, null);
        assertTrue(complete.contains("  4000 | line-4000"));
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
        assertTrue(reader.read("partial.java", 1, 4000, null, "session-a").contains("offset=4001"));
        assertTrue(reader.read("partial.java", 1, 4000, null, "session-a")
                .contains("继续当前文件请用 offset=4001"));
    }

    @Test
    void focusLineReadsLocalWindowAndRejectsConflictingRanges() throws IOException {
        StringBuilder body = new StringBuilder();
        for (int line = 1; line <= 1000; line++) body.append("line-").append(line).append('\n');
        Files.write(workspace.resolve("focus.java"), body.toString().getBytes(StandardCharsets.UTF_8));
        BoundedReadTool reader = tool();
        String focused = reader.readResult("focus.java", null, null, null, null,
                500, 2, null, "s").getContent();
        assertTrue(focused.contains("   498 | line-498"));
        assertTrue(focused.contains("   502 | line-502"));
        assertFalse(focused.contains("line-497"));
        assertTrue(reader.readResult("focus.java", 1, 10, null, null,
                500, 2, null, "s").getContent().contains("不能与 offset/limit"));
    }

    @Test
    void equivalentPathsAndDefaultRangesReuseTheSameFile() throws IOException {
        ContextArtifactStore store = new ContextArtifactStore(workspace.resolve("artifacts"));
        BoundedReadTool reader = cachedTool(store);
        Files.write(workspace.resolve("alias.txt"), "original body\n".getBytes(StandardCharsets.UTF_8));

        assertTrue(reader.read("alias.txt", null, null, null, "session-a").contains("original body"));
        String reference = reader.read("./alias.txt", 1, BoundedReadTool.DEFAULT_LINES, null, "session-a");
        assertTrue(reference.startsWith("<read-unchanged>"));
        assertTrue(reference.contains("未发生变更，请参考本会话上次 read"));
        assertFalse(reference.contains("original body"));
        assertEquals(referenceId(reference), referenceId(
                reader.read("@workspace/alias.txt", 1, BoundedReadTool.DEFAULT_LINES, null, "session-a")));
        assertFalse(reader.read("alias.txt", 1, 1, null, "session-a").startsWith("<read-unchanged>"));
    }

    @Test
    void contentChangeOutsideRequestedRangeInvalidatesReferenceEvenWithSameSizeAndTime() throws IOException {
        BoundedReadTool reader = cachedTool(new ContextArtifactStore(workspace.resolve("artifacts")));
        Path source = workspace.resolve("version.txt");
        Files.write(source, "first\nold\n".getBytes(StandardCharsets.UTF_8));
        FileTime modified = Files.getLastModifiedTime(source);
        reader.read("version.txt", 1, 1, null, "session-a");
        assertTrue(reader.read("version.txt", 1, 1, null, "session-a").startsWith("<read-unchanged>"));

        Files.write(source, "first\nnew\n".getBytes(StandardCharsets.UTF_8));
        Files.setLastModifiedTime(source, modified);
        String fresh = reader.read("version.txt", 1, 1, null, "session-a");
        assertFalse(fresh.startsWith("<read-unchanged>"));
        assertTrue(fresh.contains("1 | first"));
        assertFalse(fresh.contains(FileVersion.sha256(source)));
        org.noear.solon.ai.chat.tool.ToolResult reference = reader.readResult(
                "version.txt", 1, 1, null, null, null, "session-a");
        assertEquals(FileVersion.sha256(source), reference.metas().get("sourceHash"));
        assertFalse(reference.getContent().contains("SHA-256"));
        assertTrue(reader.read("version.txt", 1, 1, null, "session-a").startsWith("<read-unchanged>"));
        assertTrue(reader.read("version.txt", 2, 1, null, "session-a").contains("2 | new"));
    }

    @Test
    void missingSnapshotIsRebuiltBeforeReferencesResume() throws IOException {
        ContextArtifactStore store = new ContextArtifactStore(workspace.resolve("artifacts"));
        BoundedReadTool reader = cachedTool(store);
        Files.write(workspace.resolve("repair.txt"), "recoverable\n".getBytes(StandardCharsets.UTF_8));
        String first = reader.read("repair.txt", 1, 1, null, "session-a");
        String oldId = referenceId(reader.read("repair.txt", 1, 1, null, "session-a"));
        Files.delete(store.bodyPath("session-a", oldId));

        assertEquals(first, reader.read("repair.txt", 1, 1, null, "session-a"));
        String reference = reader.read("repair.txt", 1, 1, null, "session-a");
        assertTrue(reference.startsWith("<read-unchanged>"));
        assertFalse(oldId.equals(referenceId(reference)));
        assertTrue(store.restore("session-a", referenceId(reference), 0, first.length()).endsWith(first));
    }

    @Test
    void emptyFilesAreReferencedAndTheirLaterContentIsRead() throws IOException {
        BoundedReadTool reader = cachedTool(new ContextArtifactStore(workspace.resolve("artifacts")));
        Path source = Files.createFile(workspace.resolve("empty.txt"));
        assertTrue(reader.read("empty.txt", null, null, null, "session-a").contains("文件内容为空"));
        assertTrue(reader.read("empty.txt", null, null, null, "session-a").startsWith("<read-unchanged>"));
        Files.write(source, "now populated\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(reader.read("empty.txt", null, null, null, "session-a").contains("1 | now populated"));
    }

    @Test
    void failedArchivingAndInvalidRangesNeverBecomeReferences() throws IOException {
        ContextArtifactStore failingStore = new ContextArtifactStore(workspace.resolve("artifacts")) {
            @Override
            public Artifact save(String sessionId, String invocationId, String toolName,
                                 String content, String reason, int previewChars) throws IOException {
                throw new IOException("Snapshot unavailable");
            }
        };
        Files.write(workspace.resolve("failure.txt"), "keep body\n".getBytes(StandardCharsets.UTF_8));
        BoundedReadTool reader = cachedTool(failingStore);
        assertTrue(reader.read("failure.txt", 1, 1, null, "session-a").contains("1 | keep body"));
        assertTrue(reader.read("failure.txt", 1, 1, null, "session-a").contains("1 | keep body"));

        BoundedReadTool healthy = cachedTool(new ContextArtifactStore(workspace.resolve("healthy")));
        assertTrue(healthy.read("failure.txt", 10, 1, null, "session-a").startsWith("错误："));
        assertTrue(healthy.read("failure.txt", 10, 1, null, "session-a").startsWith("错误："));
    }

    /**
     * 创建带会话快照仓储的读取工具。
     *
     * @param store 快照仓储
     * @return 读取工具
     */
    private BoundedReadTool cachedTool(ContextArtifactStore store) {
        return new BoundedReadTool(new TerminalTalent(new MountManager(workspace.toString())),
                workspace.toString(), store);
    }

    /**
     * 从工具引用中取出可恢复的快照 ID。
     *
     * @param reference 未变化读取的工具结果
     * @return 快照 ID
     */
    private static String referenceId(String reference) {
        int start = reference.indexOf("@tool-results/") + "@tool-results/".length();
        return reference.substring(start, reference.indexOf(".txt", start));
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
