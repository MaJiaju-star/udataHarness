package org.noear.solon.ai.talents.cli;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.annotation.Param;

/**
 * 在工具结果治理将其归档之前，保留执行器输出的有界原始文本。
 */
public final class BashCaptureTalent extends AbsTalent {
    // ProcessExecutor 的物理捕获上限是 1 MiB；向它请求这么多可见字符，
    // 可以避免它更早地进行首尾截断。
    static final int CAPTURE_CHARS = 1024 * 1024;
    private final TerminalTalent terminal;

    public BashCaptureTalent(TerminalTalent terminal) {
        this.terminal = terminal;
    }

    @Override
    public String getInstruction(Prompt prompt) {
        return "bash 的长输出会先保留有界原文，再给出诊断摘要和 @tool-results 只读路径；超过执行器 1 MiB 物理上限时命令仍会终止，结果会标明不完整。";
    }

    /**
     * 执行非交互式命令，并在读取前按物理捕获上限请求可见输出。
     * <p>
     * 所有 shell 翻译、沙箱、校验和 HITL 仍走原有的 {@link TerminalTalent#bash} 路由，
     * 只有提前的文本裁剪发生了变化：忽略调用方建议的显示字符数，强制请求 {@link #CAPTURE_CHARS}。
     *
     * @param command       要执行的命令；多行命令支持由当前 shell 解释
     * @param timeout       超时时间（毫秒），为空时使用默认值
     * @param maxOutputChars 向模型显示的建议字符上限（本实现忽略此值，始终按物理上限捕获）
     * @param __cwd         由框架注入的工作目录（逻辑路径），为空时使用默认工作区
     * @return 有界原始文本的工具结果（外层治理层随后再进行截断与归档）
     */
    @ToolMapping(name = "bash", description = "在当前终端执行非交互式命令；实际 shell 类型以环境状态为准（可能是 PowerShell、CMD 或 Unix shell）。支持逻辑路径和多行脚本，含空格参数请加引号。最多捕获 1 MiB 文本；长结果给出诊断摘要及恢复入口，物理上限超出仍会终止进程。")
    public String bash(
            @Param(value = "command", description = "要执行的命令；多行命令支持由当前 shell 解释") String command,
            @Param(value = "timeout", required = false, defaultValue = "120000", description = "超时时间，毫秒") Integer timeout,
            @Param(value = "max_output_chars", required = false, defaultValue = "64000", description = "向模型显示的建议字符上限；完整捕获仍由 1 MiB 物理上限约束") Integer maxOutputChars,
            String __cwd) {
        // 1. 忽略建议显示长度，统一按物理捕获上限读取，避免提前首尾截断
        // 2. 交给原 TerminalTalent 执行（shell 翻译、沙箱、校验和 HITL 均不变）
        return terminal.bash(command, timeout, CAPTURE_CHARS, __cwd);
    }
}
