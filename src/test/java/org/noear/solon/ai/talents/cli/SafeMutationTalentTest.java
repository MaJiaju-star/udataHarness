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

        assertTrue(tool.edit("edit.txt", Arrays.asList(first, bad), null, "session-a").contains("编辑失败"));
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
    }

    private static TerminalTalent.EditOp edit(String oldText, String newText, int line) {
        TerminalTalent.EditOp operation = new TerminalTalent.EditOp();
        operation.oldStr = oldText;
        operation.newStr = newText;
        operation.oldStrStartLine = line;
        return operation;
    }

    private static String read(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
}
