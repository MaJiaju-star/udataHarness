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

    private SafeMutationTalent tool() {
        return new SafeMutationTalent(new TerminalTalent(new MountManager(workspace.toString())),
                workspace.toString(), true, true);
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
    void createsFilesButRequiresVersionToOverwrite() throws IOException {
        SafeMutationTalent tool = tool();
        Path target = workspace.resolve("example.txt");
        assertTrue(tool.write("example.txt", "one", null, null).contains("成功"));
        assertTrue(tool.write("example.txt", "two", null, null).contains("版本冲突"));
        assertEquals("one", read(target));

        String version = FileVersion.sha256(target);
        assertTrue(tool.write("example.txt", "two", version, null).contains("成功"));
        assertEquals("two", read(target));
        assertTrue(tool.write("example.txt", "three", version, null).contains("版本冲突"));
        assertEquals("two", read(target));
    }

    @Test
    void editPreservesBomAndCrLfAndDoesNotWritePartialResult() throws IOException {
        Path target = workspace.resolve("edit.txt");
        Files.write(target, "\uFEFFfirst\r\nsecond\r\n".getBytes(StandardCharsets.UTF_8));
        SafeMutationTalent tool = tool();
        TerminalTalent.EditOp first = edit("first", "updated", 1);
        TerminalTalent.EditOp bad = edit("missing", "nope", 2);
        String version = FileVersion.sha256(target);

        assertTrue(tool.edit("edit.txt", Arrays.asList(first, bad), version, null).contains("编辑失败"));
        assertEquals("\uFEFFfirst\r\nsecond\r\n", read(target));
        assertTrue(tool.edit("edit.txt", Arrays.asList(first), version, null).contains("成功"));
        assertEquals("\uFEFFupdated\r\nsecond\r\n", read(target));
        assertTrue(tool.edit("edit.txt", Arrays.asList(first), version, null).contains("版本冲突"));
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
