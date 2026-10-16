package org.noear.solon.ai.talents.cli;

import com.udata.harness.common.context.ContextArtifactStore;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.annotation.Param;

/**
 * 在原终端安全路由内流式归档命令输出，展示预算不再作为终止条件。
 */
public final class BashCaptureTalent extends AbsTalent {
    // ProcessExecutor 的物理捕获上限是 1 MiB；向它请求这么多可见字符，
    // 可以避免它更早地进行首尾截断。
    static final int CAPTURE_CHARS = 1024 * 1024;
    private final TerminalTalent terminal;
    private final ContextArtifactStore store;
    private final StreamingProcessExecutor executor;
    private final int visibleChars;
    private final long maxLogBytes;

    public BashCaptureTalent(TerminalTalent terminal) {
        this(terminal, null, 20000, 256L * 1024 * 1024);
    }

    /**
     * 创建会话隔离的流式命令工具。
     *
     * @param terminal 原终端安全路由
     * @param store 日志归档仓储
     * @param visibleChars 模型展示字符预算
     * @param maxLogBytes 独立日志存储字节上限
     */
    public BashCaptureTalent(TerminalTalent terminal, ContextArtifactStore store, int visibleChars, long maxLogBytes) {
        if (maxLogBytes < 1 || maxLogBytes > 1024L * 1024 * 1024) {
            throw new IllegalArgumentException("bash max-log-bytes must be between 1 and 1 GiB");
        }
        this.terminal = terminal;
        this.store = store;
        this.visibleChars = Math.max(600, Math.min(visibleChars, 64000));
        this.maxLogBytes = maxLogBytes;
        this.executor = store == null ? null : StreamingProcessExecutor.install(terminal);
    }

    @Override
    public String getInstruction(Prompt prompt) {
        return "bash 输出流式保存到当前会话的 @tool-results，只展示有界首尾片段。展示截断不终止命令；超时、取消或独立日志存储上限会终止，并标明捕获不完整。";
    }

    /**
     * 执行非交互式命令，在当前会话流式保存日志并返回有界展示。
     * <p>
     * 所有 shell 翻译、沙箱、校验和 HITL 仍走原有的 {@link TerminalTalent#bash} 路由，
     * 展示预算不再影响执行，归档仓储不可用时不会无归档执行。
     *
     * @param command       要执行的命令；多行命令支持由当前 shell 解释
     * @param timeout       超时时间（毫秒），为空时使用默认值
     * @param maxOutputChars 向模型展示的正文字符预算
     * @param __cwd         由框架注入的工作目录（逻辑路径），为空时使用默认工作区
     * @param __sessionId 框架注入的会话标识
     * @return 有界展示、日志恢复入口及仅供 UI 使用的捕获元数据
     */
    @ToolMapping(name = "bash", description = "在当前终端执行非交互式命令；实际 shell 可能是 PowerShell、CMD 或 Unix shell。输出流式保存到当前会话，展示截断不停止执行；超时、取消或独立存储上限会停止并标注原因。")
    public ToolResult bashResult(
            @Param(value = "command", description = "要执行的命令；多行命令支持由当前 shell 解释") String command,
            @Param(value = "timeout", required = false, defaultValue = "120000", description = "超时时间，毫秒") Integer timeout,
            @Param(value = "max_output_chars", required = false, defaultValue = "20000", description = "正文展示字符预算，不影响命令执行或完整日志保存") Integer maxOutputChars,
            String __cwd, String __sessionId) {
        if (store == null) return ToolResult.success(terminal.bash(command, timeout, CAPTURE_CHARS, __cwd));
        int budget = maxOutputChars == null || maxOutputChars <= 0 ? visibleChars
                : Math.min(visibleChars, Math.max(600, maxOutputChars));
        StreamingProcessExecutor.Scope scope = new StreamingProcessExecutor.Scope(store, __sessionId, budget, maxLogBytes);
        String output = executor.capture(scope, () -> terminal.bash(command, timeout, budget, __cwd));
        ToolResult result = ToolResult.success(output);
        if (!scope.details.isEmpty()) result.metas().put("bashOutput", scope.details);
        return result;
    }

    /** 保留直接调用入口；生产工具调用由框架注入会话标识。 */
    public String bash(String command, Integer timeout, Integer maxOutputChars, String cwd) {
        return bashResult(command, timeout, maxOutputChars, cwd, null).getContent();
    }
}
