package org.noear.solon.ai.talents.cli;

import com.udata.harness.common.context.ContextArtifactStore;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * 同步 bash 流式捕获执行器：以装饰器方式包装 {@link ProcessExecutor}，仅接管已通过
 * TerminalTalent 校验的同步 bash 输出捕获，其余执行入口（非流式、无捕获作用域）
 * 均原样委托给被包装的原执行器。
 *
 * <p>核心行为：进程输出由独立读取线程增量解码，实时写入当前会话的只读归档
 * （{@link ContextArtifactStore}）；返回给模型的则是受展示预算约束的有界首尾片段
 * 与诊断摘要，完整日志通过 {@code @tool-results/<id>.txt} 路径回查。</p>
 *
 * <p>终止策略：展示截断不终止命令；仅在执行超时、调用方取消、日志存储配额耗尽
 * 或持久化故障时终止进程树，并在结果中标注终止原因与捕获完整性。</p>
 *
 * <p>线程安全：捕获作用域通过 {@link ThreadLocal} 绑定，仅对当前同步 bash 调用生效，
 * 不影响并行工具调用。</p>
 */
final class StreamingProcessExecutor extends ProcessExecutor {
    private final ProcessExecutor original;
    private final ThreadLocal<Scope> current = new ThreadLocal<>();

    private StreamingProcessExecutor(ProcessExecutor original) {
        this.original = original;
        super.setScriptCharset(original.getScriptCharset());
        super.setOutputCharset(original.getOutputCharset());
        super.setMaxOutputSize(original.getMaxOutputSize());
    }

    /** 保持原执行器和适配器的脚本编码设置一致。 */
    @Override
    public void setScriptCharset(java.nio.charset.Charset charset) {
        super.setScriptCharset(charset);
        original.setScriptCharset(charset);
    }

    /** 保持非 bash 执行路径的输出编码配置不变。 */
    @Override
    public void setOutputCharset(java.nio.charset.Charset charset) {
        super.setOutputCharset(charset);
        original.setOutputCharset(charset);
    }

    /** 旧物理上限仍用于非流式执行路径，不影响同步 bash 的独立配额。 */
    @Override
    public void setMaxOutputSize(int size) {
        super.setMaxOutputSize(size);
        original.setMaxOutputSize(size);
    }

    /**
     * 在首次创建包装工具时安装执行适配器；不兼容上游字段时失败关闭。
     *
     * @param terminal 宿主终端
     * @return 共享执行适配器
     */
    static StreamingProcessExecutor install(TerminalTalent terminal) {
        if (terminal.getExecutor() instanceof StreamingProcessExecutor) {
            return (StreamingProcessExecutor) terminal.getExecutor();
        }
        try {
            StreamingProcessExecutor executor = new StreamingProcessExecutor(terminal.getExecutor());
            Field field = TerminalTalent.class.getDeclaredField("executor");
            field.setAccessible(true);
            field.set(terminal, executor);
            if (terminal.getExecutor() != executor) throw new IllegalStateException("Executor installation failed");
            return executor;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Solon streaming executor is unavailable", e);
        }
    }

    /**
     * 为当前同步 bash 绑定会话归档，作用域结束即清理，不影响其他并行工具。
     *
     * @param scope 本次捕获设置
     * @param action 原 TerminalTalent.bash 路由
     * @return 原路由执行结果
     */
    String capture(Scope scope, Supplier<String> action) {
        current.set(scope);
        try { return action.get(); }
        finally { current.remove(); }
    }

    /**
     * 保留已解析的 argv、工作目录、环境与沙箱包装，仅分离日志捕获和展示预算。
     *
     * @param rootPath 已校验工作目录
     * @param fullCmd 已校验并包装的进程参数
     * @param envs 执行环境
     * @param timeoutMs 执行超时（毫秒）
     * @param maxOutputChars 原调用方的展示预算
     * @param onOutput 原调用方的输出回调
     * @return 有界展示及完整日志恢复入口
     */
    @Override
    public String executeCmd(Path rootPath, List<String> fullCmd, Map<String, String> envs,
                             Integer timeoutMs, Integer maxOutputChars, Consumer<String> onOutput) {
        Scope scope = current.get();
        if (scope == null) return original.executeCmd(rootPath, fullCmd, envs, timeoutMs, maxOutputChars, onOutput);
        Process process = null;
        boolean interrupted = false;
        try (ContextArtifactStore.StreamingArtifact log = scope.store.beginStream(
                scope.sessionId, "bash-stream:" + UUID.randomUUID(), "bash")) {
            //1. 仍使用 Solon 提供的 argv 与实时环境，非交互执行立即关闭 stdin。
            ProcessBuilder builder = new ProcessBuilder(fullCmd).directory(rootPath.toFile()).redirectErrorStream(true);
            EnvironmentResolver.applyTo(builder, envs);
            process = builder.start();
            process.getOutputStream().close();
            Process running = process;
            CompletableFuture<Void> output = new CompletableFuture<>();
            Thread reader = new Thread(() -> {
                try {
                    drain(running, log, scope);
                    output.complete(null);
                } catch (Throwable e) {
                    scope.stopReason = "日志持久化失败：" + e.getMessage();
                    TerminalSessionManager.destroyProcessTree(running);
                    output.completeExceptionally(e);
                }
            }, "solon-bash-log-reader");
            reader.setDaemon(true);
            reader.start();

            //2. 展示截断不停止命令；只有超时、取消、存储配额或持久化故障才停止。
            int timeout = timeoutMs == null || timeoutMs < 0 ? 120000 : timeoutMs;
            try {
                if (!process.waitFor(timeout, TimeUnit.MILLISECONDS)) {
                    scope.stopReason = "执行超时：运行时间超过 " + timeout + " 毫秒（已终止进程树）。";
                    TerminalSessionManager.destroyProcessTree(process);
                }
            } catch (InterruptedException e) {
                interrupted = true;
                scope.stopReason = "执行已取消（已终止进程树）。";
                TerminalSessionManager.destroyProcessTree(process);
            }
            try { output.get(5, TimeUnit.SECONDS); }
            catch (InterruptedException e) {
                interrupted = true;
                scope.stopReason = "执行已取消（已终止进程树）。";
                TerminalSessionManager.destroyProcessTree(process);
                output.get(5, TimeUnit.SECONDS);
            }
            catch (java.util.concurrent.TimeoutException e) {
                scope.stopReason = "输出管道未关闭，捕获不完整（已终止进程树）。";
                TerminalSessionManager.destroyProcessTree(process);
                process.getInputStream().close();
                output.get(2, TimeUnit.SECONDS);
            }
            ContextArtifactStore.Artifact artifact = log.finish(scope.stopReason == null);
            //3. 只返回有界首尾片段；完整日志已经提交到当前会话的只读归档。
            boolean complete = scope.stopReason == null;
            String path = "@tool-results/" + artifact.id + ".txt";
            scope.details.put("path", path);
            scope.details.put("bytes", scope.bytes);
            scope.details.put("captureComplete", complete);
            scope.details.put("exitCode", process.isAlive() ? null : process.exitValue());
            scope.details.put("terminationReason", scope.stopReason);
            scope.details.put("displayTruncated", scope.chars > scope.visibleChars);
            int exitCode = process.isAlive() ? -1 : process.exitValue();
            String preview = scope.preview(exitCode, complete);
            // 全文已经展示时，归档路径和捕获字节数只放在元数据中，避免每次重复消耗上下文。
            if (complete && scope.fullOutputVisible(exitCode)) {
                return "[exit_code=" + exitCode + "]" + (preview.isEmpty() ? "" : "\n" + preview);
            }
            String header = "<bash-output capture_complete=\"" + complete + "\">\n"
                    + (scope.stopReason == null ? "" : scope.stopReason + "\n")
                    + "[exit_code=" + (process.isAlive() ? "unknown" : process.exitValue()) + "]\n"
                    + "已捕获 " + scope.bytes + " 字节；"
                    + (complete ? "完整日志" : "已捕获部分") + "：read(file_path=\"" + path + "\")，可用 grep 搜索。\n";
            return header + preview + "\n</bash-output>";
        } catch (Exception e) {
            if (process != null) TerminalSessionManager.destroyProcessTree(process);
            if (e instanceof InterruptedException) {
                interrupted = true;
                return "执行已取消（已终止进程树）；日志未能完成提交。";
            }
            return "系统失败: 命令日志无法可靠保存，执行已停止：" + e.getMessage();
        } finally {
            if (process != null && process.isAlive()) TerminalSessionManager.destroyProcessTree(process);
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /** 增量解码与落盘，保留 Solon 的编码识别和 PowerShell CLIXML 过滤。 */
    private void drain(Process process, ContextArtifactStore.StreamingArtifact log, Scope scope) throws IOException {
        OutputDecoder decoder = new OutputDecoder(original.getOutputCharset());
        CliXmlFilter filter = CliXmlFilter.isNeeded() ? new CliXmlFilter() : null;
        try (InputStream input = process.getInputStream()) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                if (isLikelyBinary(java.util.Arrays.copyOf(buffer, count))) scope.binary = true;
                scope.rawBytes += count;
                if (scope.rawBytes > scope.maxLogBytes) {
                    scope.limitReached = true;
                    scope.stopReason = "输出超过独立日志存储上限 " + scope.maxLogBytes + " 字节（已终止进程树）。";
                    TerminalSessionManager.destroyProcessTree(process);
                    break;
                }
                byte[] chunk = filter == null ? buffer : filter.accept(buffer, count);
                scope.append(log, decoder.decode(chunk, filter == null ? count : chunk.length));
                if (filter != null) scope.append(log, filter.drainMessages());
            }
        } catch (LogPersistenceException e) {
            throw e;
        } catch (IOException e) {
            if (scope.stopReason == null) scope.stopReason = "输出读取失败，捕获不完整：" + e.getMessage();
            TerminalSessionManager.destroyProcessTree(process);
        }
        if (scope.limitReached) return;
        try {
            if (filter != null) {
                byte[] rest = filter.flush();
                scope.append(log, decoder.decode(rest, rest.length));
                scope.append(log, filter.drainMessages());
            }
            scope.append(log, decoder.flush());
        } catch (LogLimitException e) {
            TerminalSessionManager.destroyProcessTree(process);
        }
    }

    /** 单次命令的有界展示缓冲与独立存储配额。 */
    static final class Scope {
        private static final java.util.regex.Pattern DIAGNOSTIC = java.util.regex.Pattern.compile(
                "(?i)(\\berror\\b|\\bfailed\\b|\\bfailure\\b|\\bfatal\\b|exception|traceback|caused by|assertion|\\[ERROR\\])");
        final ContextArtifactStore store;
        final String sessionId;
        final int visibleChars;
        final long maxLogBytes;
        final boolean buildOrTest;
        final Map<String, Object> details = new LinkedHashMap<>();
        final StringBuilder head = new StringBuilder(), tail = new StringBuilder();
        final StringBuilder pendingLine = new StringBuilder();
        final AnsiStripper ansiStripper = new AnsiStripper();
        final List<String> firstDiagnostics = new ArrayList<>();
        final Deque<String> lastDiagnostics = new ArrayDeque<>();
        int diagnosticCount;
        long rawBytes, bytes, chars;
        volatile String stopReason;
        boolean binary;
        boolean limitReached;

        Scope(ContextArtifactStore store, String sessionId, int visibleChars, long maxLogBytes) {
            this(store, sessionId, visibleChars, maxLogBytes, false);
        }

        Scope(ContextArtifactStore store, String sessionId, int visibleChars, long maxLogBytes,
              boolean buildOrTest) {
            this.store = store;
            this.sessionId = sessionId;
            this.visibleChars = visibleChars;
            this.maxLogBytes = maxLogBytes;
            this.buildOrTest = buildOrTest;
        }

        /** 写入日志并更新有界首尾缓存；持久化错误交给调用者终止命令。 */
        void append(ContextArtifactStore.StreamingArtifact log, String piece) throws IOException {
            long size = piece.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if (bytes + size > maxLogBytes) {
                limitReached = true;
                stopReason = "输出超过独立日志存储上限 " + maxLogBytes + " 字节（已终止进程树）。";
                throw new LogLimitException(stopReason);
            }
            try { log.append(piece); }
            catch (IOException e) { throw new LogPersistenceException(e); }
            bytes += size;
            String visible = ansiStripper.strip(piece);
            chars += visible.length();
            captureDiagnostics(visible);
            int half = visibleChars / 2;
            if (head.length() < half) head.append(visible, 0, Math.min(visible.length(), half - head.length()));
            tail.append(visible);
            if (tail.length() > visibleChars) tail.delete(0, tail.length() - visibleChars);
        }

        boolean fullOutputVisible(int exitCode) {
            return !binary && chars <= visibleChars
                    && !(buildOrTest && exitCode == 0 && chars > 3000)
                    && !(exitCode != 0 && chars > 2000);
        }

        /** 返回有界首尾片段，避免在 UTF-16 代理对中间截断。 */
        String preview() { return preview(0, true); }

        String preview(int exitCode, boolean complete) {
            if (binary) return "[检测到二进制输出，已省略模型可见正文；归档保存的是解码文本，不是原始二进制文件。]";
            if (pendingLine.length() > 0) {
                rememberDiagnostic(pendingLine.toString());
                pendingLine.setLength(0);
            }
            if ((exitCode != 0 || !complete) && chars > 2000) {
                StringBuilder result = new StringBuilder("[失败诊断] 已捕获诊断行 ")
                        .append(diagnosticCount).append(" 条；完整日志见上方路径。\n");
                for (String line : firstDiagnostics) result.append("- ").append(line).append('\n');
                if (diagnosticCount > firstDiagnostics.size() + lastDiagnostics.size()) {
                    result.append("- …中间诊断行省略…\n");
                }
                for (String line : lastDiagnostics) result.append("- ").append(line).append('\n');
                int tailBudget = Math.max(0, Math.min(2000, visibleChars - result.length() - 64));
                if (tailBudget > 0) result.append("末尾输出：\n")
                        .append(safeTail(tail, tailBudget));
                if (result.length() < visibleChars) return result.toString();
            }
            if (buildOrTest && exitCode == 0 && chars > 3000) {
                int headChars = Math.min(300, head.length());
                int tailChars = Math.min(1300, tail.length());
                return safeHead(head, headChars) + "\n[构建/测试成功；常规日志已省略，完整日志见上方路径]\n"
                        + safeTail(tail, tailChars);
            }
            if (chars <= visibleChars) return tail.toString();
            int end = head.length();
            if (end > 0 && Character.isHighSurrogate(head.charAt(end - 1))) end--;
            int start = Math.max(0, tail.length() - (visibleChars - visibleChars / 2));
            if (start < tail.length() && Character.isLowSurrogate(tail.charAt(start))) start++;
            return head.substring(0, end) + "\n[展示已截断；命令不因此终止，完整日志见上方路径]\n" + tail.substring(start);
        }

        private void captureDiagnostics(String piece) {
            int start = 0;
            for (int i = 0; i < piece.length(); i++) {
                if (piece.charAt(i) != '\n') continue;
                appendLinePart(piece, start, i);
                rememberDiagnostic(pendingLine.toString());
                pendingLine.setLength(0);
                start = i + 1;
            }
            appendLinePart(piece, start, piece.length());
        }

        private void appendLinePart(String piece, int start, int end) {
            if (pendingLine.length() < 1024 && end > start) {
                pendingLine.append(piece, start, Math.min(end, start + 1024 - pendingLine.length()));
            }
        }

        private void rememberDiagnostic(String line) {
            if (!DIAGNOSTIC.matcher(line).find()) return;
            diagnosticCount++;
            String bounded = line.trim();
            if (bounded.length() > 240) bounded = bounded.substring(0, 240) + "…";
            if (firstDiagnostics.size() < 3) firstDiagnostics.add(bounded);
            else {
                lastDiagnostics.addLast(bounded);
                if (lastDiagnostics.size() > 3) lastDiagnostics.removeFirst();
            }
        }

        private static String safeHead(CharSequence value, int length) {
            int end = Math.min(value.length(), length);
            if (end > 0 && Character.isHighSurrogate(value.charAt(end - 1))) end--;
            return value.subSequence(0, end).toString();
        }

        private static String safeTail(CharSequence value, int length) {
            int start = Math.max(0, value.length() - length);
            if (start < value.length() && Character.isLowSurrogate(value.charAt(start))) start++;
            return value.subSequence(start, value.length()).toString();
        }
    }

    /** 仅清理模型可见文本；会话归档仍保留进程原始输出中的 ANSI 序列。 */
    private static final class AnsiStripper {
        private int state; // 0=正文，1=ESC，2=CSI，3=OSC，4=OSC 后的 ESC

        String strip(String piece) {
            if (state == 0 && piece.indexOf('\u001b') < 0) return piece;
            StringBuilder result = new StringBuilder(piece.length());
            for (int i = 0; i < piece.length(); i++) {
                char c = piece.charAt(i);
                switch (state) {
                    case 0:
                        if (c == '\u001b') state = 1;
                        else result.append(c);
                        break;
                    case 1:
                        state = c == '[' ? 2 : c == ']' ? 3 : 0;
                        break;
                    case 2:
                        if (c >= '@' && c <= '~') state = 0;
                        break;
                    case 3:
                        if (c == '\u0007') state = 0;
                        else if (c == '\u001b') state = 4;
                        break;
                    default:
                        state = c == '\\' ? 0 : c == '\u001b' ? 4 : 3;
                }
            }
            return result.toString();
        }
    }

    private static final class LogPersistenceException extends IOException {
        LogPersistenceException(IOException cause) { super(cause.getMessage(), cause); }
    }

    private static final class LogLimitException extends IOException {
        LogLimitException(String message) { super(message); }
    }
}
