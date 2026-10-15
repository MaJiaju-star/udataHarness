package org.noear.solon.ai.talents.cli;

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
    void defaultsToFiveHundredLinesAndProvidesContinuation() throws IOException {
        StringBuilder contents = new StringBuilder();
        for (int i = 1; i <= 502; i++) contents.append("line-").append(i).append('\n');
        Files.write(workspace.resolve("lines.txt"), contents.toString().getBytes(StandardCharsets.UTF_8));

        String first = tool().read("lines.txt", null, null, null);
        assertTrue(first.contains("   500 | line-500"));
        assertFalse(first.contains("line-501"));
        assertTrue(first.contains("offset=501, limit=500"));
        assertTrue(first.contains("文件版本 SHA-256: " + FileVersion.sha256(workspace.resolve("lines.txt"))));
        String second = tool().read("lines.txt", 501, null, null);
        assertTrue(second.contains("line-502"));
        assertTrue(second.contains("[文件读取完毕]"));
    }

    @Test
    void giantLineIsBoundedAndNextLineRemainsReadable() throws IOException {
        String large = repeat('x', 2_000_000) + "\nsecond\n";
        Files.write(workspace.resolve("giant.txt"), large.getBytes(StandardCharsets.UTF_8));

        String first = tool().read("giant.txt", 1, 1, null);
        assertTrue(first.length() < 3000);
        assertTrue(first.contains("单行已截断"));
        assertTrue(first.contains("offset=2"));
        assertTrue(tool().read("giant.txt", 2, 1, null).contains("second"));
        assertTrue(tool().read("@workspace/giant.txt", 1, 1, null).length() < 3000);
    }

    @Test
    void byteBudgetDoesNotLoseTheNextLine() throws IOException {
        StringBuilder contents = new StringBuilder();
        for (int i = 1; i <= 1000; i++) contents.append(i).append(' ').append(repeat('a', 120)).append('\n');
        Files.write(workspace.resolve("budget.txt"), contents.toString().getBytes(StandardCharsets.UTF_8));

        String page = tool().read("budget.txt", 1, 2000, null);
        assertTrue(page.contains("字节上限"));
        assertTrue(page.length() < 52_000);
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
