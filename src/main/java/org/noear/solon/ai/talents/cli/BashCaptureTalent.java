package org.noear.solon.ai.talents.cli;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.annotation.Param;

/** Preserves the executor's bounded raw text until tool-result governance archives it. */
public final class BashCaptureTalent extends AbsTalent {
    // ProcessExecutor's physical capture cap is 1 MiB; asking it for this many
    // visible characters prevents its earlier head/tail truncation.
    static final int CAPTURE_CHARS = 1024 * 1024;
    private final TerminalTalent terminal;

    public BashCaptureTalent(TerminalTalent terminal) {
        this.terminal = terminal;
    }

    @Override
    public String getInstruction(Prompt prompt) {
        return "bash 的长输出会先保留有界原文，再给出诊断摘要和 @tool-results 只读路径；超过执行器 1 MiB 物理上限时命令仍会终止，结果会标明不完整。";
    }

    @ToolMapping(name = "bash", description = "在当前终端执行非交互式命令；实际 shell 类型以环境状态为准（可能是 PowerShell、CMD 或 Unix shell）。支持逻辑路径和多行脚本，含空格参数请加引号。最多捕获 1 MiB 文本；长结果给出诊断摘要及恢复入口，物理上限超出仍会终止进程。")
    public String bash(
            @Param(value = "command", description = "要执行的命令；多行命令支持由当前 shell 解释") String command,
            @Param(value = "timeout", required = false, defaultValue = "120000", description = "超时时间，毫秒") Integer timeout,
            @Param(value = "max_output_chars", required = false, defaultValue = "64000", description = "向模型显示的建议字符上限；完整捕获仍由 1 MiB 物理上限约束") Integer maxOutputChars,
            String __cwd) {
        // All shell translation, sandboxing, validation and HITL remain on the
        // original TerminalTalent/bash route. Only the early text clipping changes.
        return terminal.bash(command, timeout, CAPTURE_CHARS, __cwd);
    }
}
