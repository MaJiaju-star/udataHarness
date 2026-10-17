package org.noear.solon.ai.talents.cli;

import com.udata.harness.common.context.ContextArtifactStore;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.annotation.Param;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Map;

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
    private final Path workspace;

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
        this(terminal, store, visibleChars, maxLogBytes, null);
    }

    public BashCaptureTalent(TerminalTalent terminal, ContextArtifactStore store, int visibleChars,
                             long maxLogBytes, String workspace) {
        if (maxLogBytes < 1 || maxLogBytes > 1024L * 1024 * 1024) {
            throw new IllegalArgumentException("bash max-log-bytes must be between 1 and 1 GiB");
        }
        this.terminal = terminal;
        this.store = store;
        this.visibleChars = Math.max(600, Math.min(visibleChars, 64000));
        this.maxLogBytes = maxLogBytes;
        this.workspace = workspace == null ? null : Paths.get(workspace).toAbsolutePath().normalize();
        this.executor = store == null ? null : StreamingProcessExecutor.install(terminal);
    }

    @Override
    public String getInstruction(Prompt prompt) {
        return "bash 使用当前终端方言执行命令（Windows 默认 PowerShell 7，支持多行、&&、||、>；显式切换时以实际终端为准）。PowerShell 查询状态时只选需要的属性，避免对整个对象使用 Format-List。构建验证优先用 verify(build_system=auto, task=test/build/compile)。短结果直接显示退出码和正文，长结果归档到 @tool-results，失败显示诊断。";
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
    @ToolMapping(name = "bash", description = "在当前 shell 执行非交互式命令；Windows 默认 PowerShell 7，支持多行、&&、||、>，显式切换时遵循所选 shell。输出流式归档到 @tool-results，可用 read/grep 回查；模型只看有界摘要，失败优先显示诊断。展示截断不终止命令；超时或日志配额耗尽会标注不完整。")
    public ToolResult bashResult(
            @Param(value = "command", description = "要执行的命令；多行命令支持由当前 shell 解释") String command,
            @Param(value = "timeout", required = false, defaultValue = "120000", description = "超时时间，毫秒") Integer timeout,
            @Param(value = "max_output_chars", required = false, defaultValue = "20000", description = "正文展示字符预算，不影响命令执行或完整日志保存") Integer maxOutputChars,
            String __cwd, String __sessionId) {
        if (store == null) return ToolResult.success(terminal.bash(command, timeout, CAPTURE_CHARS, __cwd));
        int budget = maxOutputChars == null || maxOutputChars <= 0 ? visibleChars
                : Math.min(visibleChars, Math.max(600, maxOutputChars));
        StreamingProcessExecutor.Scope scope = new StreamingProcessExecutor.Scope(
                store, __sessionId, budget, maxLogBytes, isBuildOrTest(command));
        String output = executor.capture(scope, () -> terminal.bash(command, timeout, budget, __cwd));
        ToolResult result = ToolResult.success(output);
        if (!scope.details.isEmpty()) result.metas().put("bashOutput", scope.details);
        return result;
    }

    /** 保留直接调用入口；生产工具调用由框架注入会话标识。 */
    public String bash(String command, Integer timeout, Integer maxOutputChars, String cwd) {
        return bashResult(command, timeout, maxOutputChars, cwd, null).getContent();
    }

    @ToolMapping(name = "verify", description = "运行构建或测试并返回通过状态、诊断行及完整日志路径。自动识别 Maven/Gradle/Node 项目；按任务生成单条跨平台命令，无需 shell 拼接。")
    public ToolResult verify(
            @Param(value = "task", description = "test、build 或 compile") String task,
            @Param(value = "build_system", required = false, description = "auto（默认）、maven、gradle、npm、pnpm 或 yarn") String buildSystem,
            @Param(value = "timeout", required = false, defaultValue = "120000", description = "超时时间，毫秒") Integer timeout,
            String __cwd, String __sessionId) {
        if (store == null) return ToolResult.success("错误：verify 需要会话日志归档支持。");
        if (!"test".equals(task) && !"build".equals(task) && !"compile".equals(task)) {
            return ToolResult.success("错误：task 只能是 test、build 或 compile。");
        }
        Path directory = __cwd == null ? workspace : Paths.get(__cwd).toAbsolutePath().normalize();
        if (directory == null || !Files.isDirectory(directory)) {
            return ToolResult.success("错误：当前工作目录无效，无法识别构建项目。");
        }
        String system = buildSystem == null || buildSystem.isEmpty() ? "auto" : buildSystem.toLowerCase(java.util.Locale.ROOT);
        if ("auto".equals(system)) system = detectBuildSystem(directory);
        String command = verifyCommand(system, task, directory);
        if (command == null) return ToolResult.success("错误：无法识别构建系统，或该系统不支持 task=" + task
                + "；请指定 build_system，或使用 bash 执行自定义命令。");
        StreamingProcessExecutor.Scope scope = new StreamingProcessExecutor.Scope(
                store, __sessionId, Math.min(visibleChars, 4000), maxLogBytes, true);
        String raw = executor.capture(scope, () -> terminal.bash(command, timeout, 4000, __cwd));
        ToolResult result;
        if (scope.details.isEmpty()) {
            result = ToolResult.success("[验证未完成] " + system + " " + task + "\n" + raw);
        } else {
            Map<String, Object> details = scope.details;
            boolean complete = Boolean.TRUE.equals(details.get("captureComplete"));
            Object exit = details.get("exitCode");
            boolean passed = complete && Integer.valueOf(0).equals(exit);
            StringBuilder summary = new StringBuilder(!complete ? "[验证未完成] "
                    : passed ? "[验证通过] " : "[验证失败] ")
                    .append(system).append(' ').append(task).append("（exit_code=")
                    .append(exit == null ? "unknown" : exit).append("）\n");
            if (!complete) summary.append("捕获不完整：").append(details.get("terminationReason")).append('\n');
            if (!passed) {
                summary.append("诊断行 ").append(scope.diagnosticCount).append(" 条：\n");
                for (String line : scope.firstDiagnostics) summary.append("- ").append(line).append('\n');
                if (scope.diagnosticCount > scope.firstDiagnostics.size() + scope.lastDiagnostics.size()) {
                    summary.append("- …其余诊断见完整日志…\n");
                }
                for (String line : scope.lastDiagnostics) summary.append("- ").append(line).append('\n');
                if (scope.diagnosticCount == 0) summary.append("未识别到错误行；请查看完整日志末尾。\n");
            }
            summary.append("日志：").append(details.get("path"))
                    .append("；可用 read/grep 回查。\n");
            result = ToolResult.success(summary.toString());
            result.metas().put("bashOutput", details);
        }
        return result;
    }

    private static String detectBuildSystem(Path directory) {
        if (Files.isRegularFile(directory.resolve("pom.xml"))) return "maven";
        if (Files.isRegularFile(directory.resolve("build.gradle"))
                || Files.isRegularFile(directory.resolve("build.gradle.kts"))) return "gradle";
        if (Files.isRegularFile(directory.resolve("package.json"))) {
            if (Files.isRegularFile(directory.resolve("pnpm-lock.yaml"))) return "pnpm";
            if (Files.isRegularFile(directory.resolve("yarn.lock"))) return "yarn";
            return "npm";
        }
        return "unknown";
    }

    private static String verifyCommand(String system, String task, Path directory) {
        boolean windows = EnvironmentResolver.isWindows();
        String goal;
        switch (system) {
            case "maven":
                goal = "build".equals(task) ? "package" : task;
                String maven = Files.isRegularFile(directory.resolve(windows ? "mvnw.cmd" : "mvnw"))
                        ? windows ? ".\\mvnw.cmd" : "./mvnw" : windows ? "mvn.cmd" : "mvn";
                return maven + " " + goal;
            case "gradle":
                goal = "compile".equals(task) ? "classes" : task;
                String gradle = Files.isRegularFile(directory.resolve(windows ? "gradlew.bat" : "gradlew"))
                        ? windows ? ".\\gradlew.bat" : "./gradlew" : windows ? "gradle.bat" : "gradle";
                return gradle + " " + goal + " --console=plain";
            case "npm": case "pnpm": case "yarn":
                if ("compile".equals(task)) return null;
                String executable = system + (windows ? ".cmd" : "");
                return executable + ("build".equals(task) && "npm".equals(system) ? " run build" : " " + task);
            default:
                return null;
        }
    }

    private static boolean isBuildOrTest(String command) {
        return command != null && command.matches(
                "(?is).*\\b(?:mvn|gradle|gradlew|npm|pnpm|yarn)(?:\\.cmd|\\.bat)?\\b"
                        + ".*\\b(?:test|build|package|verify|check|install)\\b.*");
    }
}
