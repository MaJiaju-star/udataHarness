package org.noear.solon.ai.talents.cli;

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
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertSame;

class SafeMutationTalentTest {
    @TempDir Path workspace;
    private final SessionFileVersions versions = new SessionFileVersions();

    @Test
    void successfulEditsExposeReviewMetadataWithoutSendingPatchInModelText() throws IOException {
        SafeMutationTalent mutation = tool();
        mutation.write("diff.txt", "one\nold\nlast\n", null, "s");
        org.noear.solon.ai.chat.tool.ToolResult result = mutation.editResult("diff.txt",
                Arrays.asList(edit("old", "new", 2)), null, "s", null);
        java.util.Map<?, ?> diff = (java.util.Map<?, ?>) result.metas().get("editDiff");
        assertEquals(1, diff.get("addedLines"));
        assertEquals(1, diff.get("deletedLines"));
        assertEquals(2, diff.get("firstChangedLine"));
        assertTrue(((String) diff.get("patch")).contains("-old\n+new\n"));
        assertFalse(result.getContent().contains("--- a/"));
        Files.write(workspace.resolve("diff.txt"), "external".getBytes(StandardCharsets.UTF_8));
        org.noear.solon.ai.chat.tool.ToolResult conflict = mutation.editResult("diff.txt",
                Arrays.asList(edit("new", "next", 2)), null, "s", null);
        assertTrue(conflict.getContent().contains("版本冲突"));
        assertFalse(conflict.metas().containsKey("editDiff"));
    }

    private SafeMutationTalent tool() {
        return new SafeMutationTalent(new TerminalTalent(new MountManager(workspace.toString())),
                workspace.toString(), true, true, versions);
    }

    @Test
    void toolResultSnapshotsAreReadOnly() throws IOException {
        String path = "@tool-results/" + repeat('a', 64) + ".txt";
        assertTrue(tool().write(path, "new", null, null).contains("只读"));
        assertTrue(tool().edit(path, Arrays.asList(new TerminalTalent.EditOp()), null, null)
                .contains("只读"));
    }

    private static String repeat(char value, int count) {
        char[] chars = new char[count];
        Arrays.fill(chars, value);
        return new String(chars);
    }

    @Test
    void automaticallyTracksOwnWritesAndRejectsOtherSessionsAndExternalChanges() throws IOException {
        SafeMutationTalent tool = tool();
        Path target = workspace.resolve("example.txt");
        assertTrue(tool.write("example.txt", "one", null, "session-a").contains("成功"));
        assertTrue(tool.write("example.txt", "two", null, "session-b").contains("版本冲突"));
        assertTrue(tool.write("example.txt", "two", null, "session-a").contains("成功"));
        assertEquals("two", read(target));
        Files.write(target, "external".getBytes(StandardCharsets.UTF_8));
        assertTrue(tool.write("example.txt", "three", null, "session-a").contains("版本冲突"));
        reader().read("example.txt", null, null, null, "session-b");
        assertTrue(tool.write("example.txt", "three", null, "session-a").contains("版本冲突"));
        assertEquals("external", read(target));
        reader().read("./example.txt", null, null, null, "session-a");
        assertTrue(tool.write("example.txt", "three", null, "session-a").contains("成功"));
        assertEquals("three", read(target));
    }

    @Test
    void editPreservesBomAndCrLfAndDoesNotWritePartialResult() throws IOException {
        Path target = workspace.resolve("edit.txt");
        Files.write(target, "\uFEFFfirst\r\nsecond\r\n".getBytes(StandardCharsets.UTF_8));
        SafeMutationTalent tool = tool();
        TerminalTalent.EditOp first = edit("first", "updated", 1);
        TerminalTalent.EditOp bad = edit("missing", "nope", 2);
        reader().read("edit.txt", null, null, null, "session-a");

        assertTrue(tool.edit("edit.txt", Arrays.asList(first, bad), null, "session-a").contains("预检查失败"));
        assertEquals("\uFEFFfirst\r\nsecond\r\n", read(target));
        assertTrue(tool.edit("edit.txt", Arrays.asList(first), null, "session-a").contains("成功"));
        assertEquals("\uFEFFupdated\r\nsecond\r\n", read(target));
        assertTrue(tool.edit("edit.txt", Arrays.asList(edit("second", "next", 2)), null,
                "session-a").contains("成功"));
        Files.write(target, "external\r\nnext\r\n".getBytes(StandardCharsets.UTF_8));
        assertTrue(tool.edit("edit.txt", Arrays.asList(edit("next", "last", 2)), null,
                "session-a").contains("版本冲突"));
    }

    @Test
    void duplicateEditReportsCandidateLinesAndLineAnchorNeverFallsBack() throws IOException {
        Files.write(workspace.resolve("repeated.txt"),
                "head\nrepeat\nmiddle\nrepeat\n".getBytes(StandardCharsets.UTF_8));
        reader().read("repeated.txt", null, null, null, "session-a");
        SafeMutationTalent mutation = tool();
        TerminalTalent.EditOp ambiguous = new TerminalTalent.EditOp();
        ambiguous.oldStr = "repeat";
        ambiguous.newStr = "changed";
        String error = mutation.edit("repeated.txt", Arrays.asList(ambiguous), null, "session-a");
        assertTrue(error.contains("候选起始行 [2, 4]"));
        assertEquals("head\nrepeat\nmiddle\nrepeat\n", read(workspace.resolve("repeated.txt")));

        assertTrue(mutation.edit("repeated.txt", Arrays.asList(edit("repeat", "changed", 3)),
                null, "session-a").contains("未回退到其他位置"));
        assertTrue(mutation.edit("repeated.txt", Arrays.asList(edit("repeat", "changed", 4)),
                null, "session-a").contains("成功"));
        assertEquals("head\nrepeat\nmiddle\nchanged\n", read(workspace.resolve("repeated.txt")));
    }

    @Test
    void lineEditsReplaceOriginalLineRangesWithoutOldTextAndKeepCrLfBom() throws IOException {
        Path target = workspace.resolve("ranges.txt");
        Files.write(target, "\uFEFFone\r\ntwo\r\nthree\r\nfour\r\n".getBytes(StandardCharsets.UTF_8));
        reader().read("ranges.txt", null, null, null, "session-a");
        SafeMutationTalent mutation = tool();
        org.noear.solon.ai.chat.tool.ToolResult result = mutation.editResult("ranges.txt", null,
                Arrays.asList(lineEdit(2, 3, "new-two\nnew-three")), null, "session-a", null);
        assertTrue(result.getContent().contains("成功"));
        assertEquals("\uFEFFone\r\nnew-two\r\nnew-three\r\nfour\r\n", read(target));
        assertTrue(result.metas().containsKey("editDiff"));
        String schema = mutation.getToolAry("edit").iterator().next().inputSchema();
        assertTrue(schema.contains("line_edits"));
        assertTrue(schema.contains("start_line"));
        assertTrue(schema.contains("end_line"));
        java.util.Map<String, Object> invocation = new java.util.LinkedHashMap<>();
        invocation.put("file_path", "ranges.txt");
        java.util.Map<String, Object> range = new java.util.LinkedHashMap<>();
        range.put("start_line", 1);
        range.put("end_line", 1);
        range.put("new_str", "");
        invocation.put("line_edits", java.util.Collections.singletonList(range));
        assertTrue(org.junit.jupiter.api.Assertions.assertDoesNotThrow(() ->
                mutation.getToolAry("edit").iterator().next().call(invocation)).getContent()
                .contains("版本冲突"));
    }

    @Test
    void lineEditsApplyFromBottomAndRejectOverlapWithoutWriting() throws IOException {
        Path target = workspace.resolve("ordered.txt");
        Files.write(target, "a\nb\nc\nd".getBytes(StandardCharsets.UTF_8));
        reader().read("ordered.txt", null, null, null, "session-a");
        SafeMutationTalent mutation = tool();
        String overlap = mutation.editResult("ordered.txt", null,
                Arrays.asList(lineEdit(2, 3, "B"), lineEdit(3, 4, "D")),
                null, "session-a", null).getContent();
        assertTrue(overlap.contains("重叠"));
        assertEquals("a\nb\nc\nd", read(target));
        String invalid = mutation.editResult("ordered.txt", null,
                Arrays.asList(lineEdit(5, 5, "missing")), null, "session-a", null).getContent();
        assertTrue(invalid.contains("超出文件范围"));
        String success = mutation.editResult("ordered.txt", null,
                Arrays.asList(lineEdit(3, 3, "C"), lineEdit(1, 1, "A"), lineEdit(4, 4, "D")),
                null, "session-a", null).getContent();
        assertTrue(success.contains("成功"));
        assertEquals("A\nb\nC\nD", read(target));
        assertTrue(mutation.editResult("ordered.txt", null,
                Arrays.asList(lineEdit(2, 2, "")), null, "session-b", null).getContent()
                .contains("尚未读取"));
    }

    @Test
    void lineEditsDeleteWholeLinesAndRejectMixedModes() throws IOException {
        Path target = workspace.resolve("delete.txt");
        Files.write(target, "a\r\nb\r\nc\r\n".getBytes(StandardCharsets.UTF_8));
        reader().read("delete.txt", null, null, null, "session-a");
        SafeMutationTalent mutation = tool();
        assertTrue(mutation.editResult("delete.txt", Arrays.asList(edit("a", "A", 1)),
                Arrays.asList(lineEdit(2, 2, "B")), null, "session-a", null).getContent()
                .contains("恰好提供一种"));
        assertTrue(mutation.editResult("delete.txt", null,
                Arrays.asList(lineEdit(2, 2, "")), null, "session-a", null).getContent()
                .contains("成功"));
        assertEquals("a\r\nc\r\n", read(target));
    }

    @Test
    void rereadingInSameBatchCannotAuthorizeAnAlreadyGeneratedEdit() throws IOException {
        Path target = workspace.resolve("batch.txt");
        Files.write(target, "old".getBytes(StandardCharsets.UTF_8));
        reader().read("batch.txt", 1, 1, null, "session-a");
        versions.beginBatch("session-a", "turn-1");
        Files.write(target, "external".getBytes(StandardCharsets.UTF_8));
        reader().read("batch.txt", 1, 1, null, "session-a");
        versions.beginBatch("session-a", "turn-1"); // 同批审批恢复不得重新冻结新版本。

        assertTrue(tool().write("batch.txt", "overwrite", null, "session-a", "turn-1")
                .contains("版本冲突"));
        assertEquals("external", read(target));

        versions.beginBatch("session-a", "turn-2");
        assertTrue(tool().write("batch.txt", "accepted", null, "session-a", "turn-2")
                .contains("成功"));
        assertTrue(tool().write("batch.txt", "second same batch", null, "session-a", "turn-2")
                .contains("版本冲突"));
        versions.beginBatch("session-a", "turn-3");
        assertTrue(tool().write("batch.txt", "next turn", null, "session-a", "turn-3")
                .contains("成功"));
    }

    @Test
    void existingFilesRequireAReadAndDeletedFilesCannotBeSilentlyRecreated() throws IOException {
        Path target = workspace.resolve("existing.txt");
        Files.write(target, "existing".getBytes(StandardCharsets.UTF_8));
        assertTrue(tool().edit("existing.txt", Arrays.asList(edit("existing", "new", 1)),
                null, "session-a").contains("尚未读取"));
        reader().read("existing.txt", 1, 1, null, "session-a");
        Files.delete(target);
        assertTrue(tool().write("existing.txt", "stale write", null, "session-a").contains("版本冲突"));
        assertFalse(Files.exists(target));
        assertTrue(reader().read("existing.txt", 1, 1, null, "session-a").contains("不存在"));
        assertTrue(tool().write("existing.txt", "create again", null, "session-a").contains("成功"));
    }

    /**
     * 构造与修改工具共享版本记录的读取工具。
     *
     * @return 当前工作区读取工具
     */
    private BoundedReadTool reader() {
        return new BoundedReadTool(new TerminalTalent(new MountManager(workspace.toString())),
                workspace.toString(), null, versions);
    }

    @Test
    void rejectsSandboxEscapeAndDoesNotExposeUnapprovedMethods() throws IOException {
        SafeMutationTalent readOnly = new SafeMutationTalent(
                new TerminalTalent(new MountManager(workspace.toString())), workspace.toString(), false, true);
        assertFalse(readOnly.getTools(null).stream().anyMatch(tool -> "write".equals(tool.name())));
        assertTrue(readOnly.getTools(null).stream().anyMatch(tool -> "edit".equals(tool.name())));
        assertThrows(SecurityException.class, () -> tool().write("../outside.txt", "x", null, null));
    }

    @Test
    void mutationTalentOverridesOnlyGrantedTools() {
        TerminalTalent terminal = new TerminalTalent(new MountManager(workspace.toString()));
        TerminalTalentProxy builtIn = new TerminalTalentProxy(terminal);
        builtIn.addTools("read", "edit");
        SafeMutationTalent replacement = new SafeMutationTalent(terminal,
                workspace.toString(), false, true);
        ModelOptionsAmend<?, ?> options = new ModelOptionsAmend<>();
        options.talentAdd(builtIn);
        options.talentAdd(replacement);
        TalentUtil.activeTalents(options, Prompt.of("test"), new StringBuilder());
        assertSame(replacement.getToolAry("edit").iterator().next(), options.tools().stream()
                .filter(item -> "edit".equals(item.name())).findFirst().orElseThrow(AssertionError::new));
        assertFalse(options.tools().stream().anyMatch(item -> "write".equals(item.name())));
        assertTrue(replacement.getToolAry("edit").iterator().next().inputSchema().contains("old_StrStartLine"));
    }

    private static TerminalTalent.EditOp edit(String oldText, String newText, int line) {
        TerminalTalent.EditOp operation = new TerminalTalent.EditOp();
        operation.oldStr = oldText;
        operation.newStr = newText;
        operation.oldStrStartLine = line;
        return operation;
    }

    private static SafeMutationTalent.LineEditOp lineEdit(int start, int end, String replacement) {
        SafeMutationTalent.LineEditOp operation = new SafeMutationTalent.LineEditOp();
        operation.startLine = start;
        operation.endLine = end;
        operation.newStr = replacement;
        return operation;
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
