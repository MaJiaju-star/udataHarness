package org.noear.solon.ai.talents.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.chat.ModelOptionsAmend;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.TalentUtil;
import org.noear.solon.ai.talents.mount.MountManager;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class BashCaptureTalentTest {
    @TempDir Path workspace;

    @Test
    void forwardsThroughTerminalWithFullPhysicalCaptureBudget() {
        RecordingTerminal terminal = new RecordingTerminal(new MountManager(workspace.toString()));
        BashCaptureTalent capture = new BashCaptureTalent(terminal);
        assertEquals("captured", capture.bash("echo test", 1234, 20, null));
        assertEquals("echo test", terminal.command);
        assertEquals(Integer.valueOf(1234), terminal.timeout);
        assertEquals(Integer.valueOf(1024 * 1024), terminal.maxOutputChars);
    }

    @Test
    void laterTalentReplacesOnlyGrantedBash() {
        RecordingTerminal terminal = new RecordingTerminal(new MountManager(workspace.toString()));
        TerminalTalentProxy builtIn = new TerminalTalentProxy(terminal);
        builtIn.addTools("bash");
        BashCaptureTalent capture = new BashCaptureTalent(terminal);
        ModelOptionsAmend<?, ?> options = new ModelOptionsAmend<>();
        options.talentAdd(builtIn);
        options.talentAdd(capture);
        TalentUtil.activeTalents(options, Prompt.of("test"), new StringBuilder());
        assertSame(capture.getToolAry("bash").iterator().next(), options.tools().stream()
                .filter(tool -> "bash".equals(tool.name())).findFirst().orElseThrow(AssertionError::new));
    }

    static final class RecordingTerminal extends TerminalTalent {
        String command;
        Integer timeout, maxOutputChars;

        RecordingTerminal(MountManager mounts) { super(mounts); }

        @Override public String bash(String command, Integer timeout, Integer maxOutputChars, String cwd) {
            this.command = command;
            this.timeout = timeout;
            this.maxOutputChars = maxOutputChars;
            return "captured";
        }
    }
}
