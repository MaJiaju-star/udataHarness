package com.udata.harness;

import com.udata.harness.common.context.ContextArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.ai.talents.cli.BashCaptureTalent;
import org.noear.solon.ai.talents.cli.TerminalTalent;
import org.noear.solon.ai.talents.mount.MountManager;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ShellBootstrapTest {
    @TempDir Path workspace;

    @Test
    void selectsPowerShell7OnlyForUnconfiguredWindows() {
        assertEquals("pwsh", ShellBootstrap.chooseDefaultShell("Windows 11", null, null, () -> true));
        assertNull(ShellBootstrap.chooseDefaultShell("Windows 11", "cmd", null,
                () -> { throw new AssertionError("explicit JVM property must bypass probe"); }));
        assertNull(ShellBootstrap.chooseDefaultShell("Windows 11", null, "powershell",
                () -> { throw new AssertionError("explicit environment must bypass probe"); }));
        assertNull(ShellBootstrap.chooseDefaultShell("Linux", null, null,
                () -> { throw new AssertionError("Unix must bypass probe"); }));
        assertThrows(IllegalStateException.class,
                () -> ShellBootstrap.chooseDefaultShell("Windows 11", null, null, () -> false));
    }

    @Test
    void powerShell7ExecutesMultilineChainAndRedirectionThroughBashTool() throws Exception {
        assumeTrue(System.getProperty("os.name", "").toLowerCase().contains("win"));
        assumeTrue(ShellBootstrap.powerShell7Available());
        String old = System.getProperty("solon.ai.cli.shell");
        System.setProperty("solon.ai.cli.shell", "pwsh");
        try {
            TerminalTalent terminal = new TerminalTalent(new MountManager(workspace.toString()));
            BashCaptureTalent bash = new BashCaptureTalent(terminal,
                    new ContextArtifactStore(workspace.resolve("artifacts")), 2000, 1024 * 1024,
                    workspace.toString());
            ToolResult result = bash.bashResult("Write-Output 'saved' > result.txt\n"
                    + "Write-Output 'step-two' && Write-Output 'step-three'",
                    15000, 2000, null, "shell-smoke");
            assertTrue(result.getContent().contains("[exit_code=0]"), result.getContent());
            assertFalse(result.getContent().contains("<bash-output"), result.getContent());
            assertTrue(result.getContent().contains("step-two"), result.getContent());
            assertTrue(result.getContent().contains("step-three"), result.getContent());
            assertEquals("saved", new String(Files.readAllBytes(workspace.resolve("result.txt")),
                    StandardCharsets.UTF_8).trim());

            ToolResult formatted = bash.bashResult("Get-Location; $PSVersionTable | Format-List; "
                    + "Get-Process -Id $PID | Format-List Name, Id, Path",
                    15000, 2000, null, "shell-format");
            assertTrue(formatted.getContent().startsWith("[exit_code=0]\n"), formatted.getContent());
            assertTrue(formatted.getContent().contains("PSVersion"), formatted.getContent());
            assertFalse(formatted.getContent().contains("\u001b["), formatted.getContent());
            assertFalse(formatted.getContent().contains("@tool-results/"), formatted.getContent());
        } finally {
            if (old == null) System.clearProperty("solon.ai.cli.shell");
            else System.setProperty("solon.ai.cli.shell", old);
        }
    }
}
