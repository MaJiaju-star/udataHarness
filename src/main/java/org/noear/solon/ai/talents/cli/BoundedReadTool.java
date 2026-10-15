package org.noear.solon.ai.talents.cli;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.annotation.Param;
import org.noear.solon.ai.talents.mount.MountManager;
import org.noear.solon.ai.talents.mount.ResolvedResource;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PushbackReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Consumer;

/**
 * Bounded replacement for the Solon 4.1.1 read tool. It deliberately reuses the
 * engine's TerminalSupport so mount, symlink and mandatory-deny checks stay in
 * one place. If that upstream private field changes, construction fails closed.
 *
 * <p>读取行为受四重上限约束，避免单次工具结果撑爆上下文：</p>
 * <ul>
 *   <li>默认返回前 {@value #DEFAULT_LINES} 行，单次最多 {@value #MAX_LINES} 行；</li>
 *   <li>单次输出总量不超过 {@value #MAX_OUTPUT_BYTES} 字节（50KB）；</li>
 *   <li>超长单行只保留前 {@value #MAX_LINE_CHARS} 字符；</li>
 *   <li>到达任一上限时在结果尾部给出续读所需的 offset/limit。</li>
 * </ul>
 *
 * <p>分页由调用方通过 {@code offset} 参数驱动；工具本身不保留任何读取状态，
 * 因此多次调用之间可安全交错（不同会话/子智能体同时读同一文件互不影响）。</p>
 *
 * @see #read(String, Integer, Integer, String)
 * @see #readWindow(Path, String, int, int, long)
 */
public final class BoundedReadTool extends AbsTalent {
    /** 默认返回行数（未显式传 limit 时生效）。 */
    static final int DEFAULT_LINES = 500;
    /** 单次读取行数硬上限（传入更大的 limit 会被截断到该值）。 */
    static final int MAX_LINES = 2000;
    /** 单行显示字符数上限；超出部分截断并标记 {@code …(单行已截断)}。 */
    static final int MAX_LINE_CHARS = 2000;
    /** 单次工具输出的 UTF-8 字节硬上限（50KB，含行号前缀与页眉）。 */
    static final int MAX_OUTPUT_BYTES = 50 * 1024;
    static final long MAX_VERSIONED_BYTES = 16L * 1024 * 1024;

    private final TerminalTalent terminal;
    private final TerminalSupport support;
    private final MountManager mountManager;
    private final Field warmupHook;
    private final Path workspace;

    /**
     * 构造工具实例，并通过反射接入 {@code TerminalTalent} 内部的读守卫。
     *
     * <p>反射仅用于复用上游的路径解析/挂载/强制拒绝校验与 LSP 预热钩子，
     * 不绕过任何安全检查；若上游私有字段改名或不可访问，则抛出
     * {@link IllegalStateException} 快速失败（fail-closed），而不是降级为
     * 无守卫的裸读。</p>
     *
     * @param terminal  宿主 Talent，提供 support、mountManager、fileWarmupHook
     * @param workspace 工作区根目录（作为 {@code __cwd} 缺省时的解析基准）
     * @throws IllegalStateException 上游私有字段不可用（版本不兼容）时抛出
     */
    public BoundedReadTool(TerminalTalent terminal, String workspace) {
        this.terminal = terminal;
        this.workspace = Paths.get(workspace).toAbsolutePath().normalize();
        try {
            Field field = TerminalTalent.class.getDeclaredField("support");
            field.setAccessible(true);
            this.support = (TerminalSupport) field.get(terminal);
            Field mountField = TerminalTalent.class.getDeclaredField("mountManager");
            mountField.setAccessible(true);
            this.mountManager = (MountManager) mountField.get(terminal);
            this.warmupHook = TerminalTalent.class.getDeclaredField("fileWarmupHook");
            this.warmupHook.setAccessible(true);
        } catch (ReflectiveOperationException | SecurityException e) {
            throw new IllegalStateException("Solon read path guard is unavailable", e);
        }
    }

    /**
     * 返回注入系统提示的分页使用指引，覆盖 Solon 默认 read 的一次性全量读取倾向。
     *
     * @param prompt 当前提示上下文（本实现未使用）
     * @return 模型可见的简短操作提示
     */
    @Override
    public String getInstruction(Prompt prompt) {
        return "read 默认只返回前 500 行；输出提示内容未完时，按 offset 分页续读。超长单行只显示前 2000 字符。";
    }

    /**
     * 分页读取文本文件（工具名 {@code read}）。
     *
     * <p>处理顺序：</p>
     * <ol>
     *   <li>{@code @} 开头的逻辑路径：非本地可访问的挂载源回落到 Solon 原生
     *       {@code TerminalTalent.read}（沿用其 10MB 守卫）；本地挂载与工作区文件
     *       走同一套有界扫描；</li>
     *   <li>经 {@code TerminalSupport.resolveSafePath} 做挂载/符号链接/强制拒绝校验；</li>
     *   <li>依次校验存在性、普通文件、非二进制、非空；</li>
     *   <li>调用 {@link #readWindow} 产出带行号前缀与续读提示的窗口文本；</li>
     *   <li>成功读取后触发 LSP 预热（尽力而为，失败不影响结果）。</li>
     * </ol>
     *
     * <p>错误场景返回可读的错误字符串而非抛异常，便于模型直接自纠。</p>
     *
     * @param filePath  工作区相对路径，或 {@code @} 开头的挂载逻辑路径
     * @param offset    起始行号（1 开始）；null 或 {@code < 1} 时按 1 处理
     * @param limit     最多读取行数；null 或 {@code < 1} 时取 {@value #DEFAULT_LINES}，
     *                  超过 {@value #MAX_LINES} 时截断
     * @param __cwd     调用方当前工作目录；null 时回落到构造时传入的 workspace
     * @return 带行号的文件窗口文本、分页续读提示，或错误说明
     * @throws IOException        读取过程中发生 I/O 错误
     * @throws IllegalArgumentException {@code filePath} 为 null
     */
    @ToolMapping(name = "read", description = "读取文本文件。默认返回最多 500 行；单次最多 2000 行、50KB，超长单行只显示前 2000 字符。结果提供下一页 offset；支持逻辑路径。")
    public String read(
            @Param(value = "file_path", description = "工作区相对路径或 @ 开头的逻辑路径") String filePath,
            @Param(value = "offset", required = false, description = "起始行号，从 1 开始") Integer offset,
            @Param(value = "limit", required = false, description = "最多读取的行数，默认 500，最大 2000") Integer limit,
            String __cwd) throws IOException {
        if (filePath == null) {
            throw new IllegalArgumentException("file_path 不能为空");
        }
        int start = offset == null || offset < 1 ? 1 : offset;
        int count = limit == null || limit < 1 ? DEFAULT_LINES : Math.min(limit, MAX_LINES);

        // Non-local virtual sources stay on Solon's MountSource read path and its
        // 10 MB guard. Local mounts use the same bounded scanner as workspace files.
        if (filePath.startsWith("@")) {
            ResolvedResource resource = mountManager.resolveResource(filePath);
            if (!resource.getSource().capabilities().isLocalPathAccessible()
                    || !resource.getLocalPath().isPresent()) {
                return terminal.read(filePath, start, count, __cwd);
            }
        }

        Path workPath = __cwd == null ? workspace : Paths.get(__cwd).toAbsolutePath().normalize();
        Path target = support.resolveSafePath(workPath, filePath, false,
                terminal.isSandboxEnabled(), false, null);
        if (!Files.exists(target)) {
            return "错误：文件不存在";
        }
        if (!Files.isRegularFile(target)) {
            return "错误：目标不是普通文件";
        }
        if (support.isNotTextFile(target)) {
            return "错误：该文件是二进制格式，无法作为文本读取。";
        }
        long fileSize = Files.size(target);
        if (fileSize == 0) {
            return "(文件内容为空)\n文件版本 SHA-256: " + FileVersion.sha256(target);
        }
        String beforeVersion = fileSize <= MAX_VERSIONED_BYTES ? FileVersion.sha256(target) : null;
        String output = readWindow(target, filePath, start, count, fileSize);
        if (beforeVersion != null) {
            if (!beforeVersion.equals(FileVersion.sha256(target))) {
                return "错误：文件在读取期间发生变化，请重新读取。";
            }
            output += "\n文件版本 SHA-256: " + beforeVersion
                    + "（覆盖或编辑时传 expected_sha256）";
        }
        warmup(target);
        return output;
    }

    /**
     * 反射读取并执行 {@code TerminalTalent.fileWarmupHook}，为刚读过的文件
     * 预热 LSP 索引；任何异常都被吞掉（与 {@code TerminalTalent.read} 行为一致）。
     *
     * @param target 已成功读取的文件路径
     */
    @SuppressWarnings("unchecked")
    private void warmup(Path target) {
        try {
            Consumer<Path> hook = (Consumer<Path>) warmupHook.get(terminal);
            if (hook != null) hook.accept(target);
        } catch (Throwable ignored) {
            // LSP warmup is best-effort, like TerminalTalent.read.
        }
    }

    /**
     * 从 {@code offset} 行起扫描文件，产出一个受限的读取窗口。
     *
     * <p>逐行读取并在累计输出达到 {@value #MAX_OUTPUT_BYTES} 字节时提前停止；
     * 行数达到 {@code limit} 后再探测一个字符判断是否仍有剩余内容。
     * 输出格式为 {@code %6d | 正文} 的行号前缀，尾部附带：截断提示、
     * 续读 offset/limit 建议，或读取完毕标记。</p>
     *
     * @param target      目标文件（调用方已确认存在且为文本文件）
     * @param displayPath 输出头中展示的路径（保持用户输入原样，不用绝对路径）
     * @param offset      起始行号，从 1 开始
     * @param limit       本窗口最多输出行数
     * @param fileSize    文件总字节数，仅用于输出头的体积展示
     * @return 组装后的窗口文本；若起始行越界则返回错误说明
     * @throws IOException 读取文件时发生 I/O 错误
     */
    static String readWindow(Path target, String displayPath, int offset, int limit, long fileSize) throws IOException {
        StringBuilder body = new StringBuilder();
        int outputBytes = 0;
        long lineNumber = 1;
        long lastLine = offset - 1L;
        boolean hasMore = false;
        boolean longLine = false;
        boolean byteLimit = false;
        int emitted = 0;

        try (PushbackReader reader = new PushbackReader(
                new BufferedReader(Files.newBufferedReader(target, StandardCharsets.UTF_8), 8192), 1)) {
            while (lineNumber < offset && readLine(reader, lineNumber == 1) != null) {
                lineNumber++;
            }
            while (emitted < limit) {
                Line line = readLine(reader, lineNumber == 1);
                if (line == null) {
                    break;
                }
                String formatted = String.format("%6d | %s%s\n", lineNumber, line.text,
                        line.truncated ? "…(单行已截断)" : "");
                int bytes = formatted.getBytes(StandardCharsets.UTF_8).length;
                if (outputBytes + bytes > MAX_OUTPUT_BYTES) {
                    byteLimit = true;
                    hasMore = true;
                    break;
                }
                body.append(formatted);
                outputBytes += bytes;
                longLine |= line.truncated;
                lastLine = lineNumber;
                lineNumber++;
                emitted++;
            }
            if (!hasMore) {
                int next = reader.read();
                hasMore = next != -1;
            }
        }

        if (emitted == 0 && !byteLimit) {
            return "错误：起始行 (" + offset + ") 已超出文件范围。";
        }
        StringBuilder result = new StringBuilder();
        result.append("--- File: ").append(displayPath).append(" (Lines: ")
                .append(offset).append(" - ").append(lastLine).append(", Size: ")
                .append(String.format("%.2f KB", fileSize / 1024.0)).append(") ---\n")
                .append("--------------------------------------------------\n")
                .append(body);
        if (longLine) {
            result.append("\n提示：超长单行仅显示前 ").append(MAX_LINE_CHARS)
                    .append(" 字符；该行剩余内容未返回。");
        }
        if (hasMore) {
            result.append("\n--- [内容未完] ---\n")
                    .append(byteLimit ? "已达到单次读取字节上限。" : "已达到本次读取行数上限。")
                    .append("若需继续，请使用 offset=").append(lastLine + 1)
                    .append(", limit=").append(limit);
        } else {
            result.append("\n--- [文件读取完毕] ---");
        }
        return result.toString();
    }

    /**
     * 从 {@code reader} 中读取一行，手动处理 {@code \r\n}、{@code \r} 换行符，
     * 超过 {@value #MAX_LINE_CHARS} 的部分截断（不消耗字符，仅丢弃），
     * 并在文件首行跳过 UTF-8 BOM。
     *
     * <p>与 {@link BufferedReader#readLine()} 的区别：保留原始终止符语义、
     * 支持单行长度上限与截断标记、可精确判定 EOF。</p>
     *
     * @param reader    可回退读取器（用于 CR 后非 LF 字符的回退）
     * @param firstLine 是否为文件第一行（决定是否跳过 BOM）
     * @return 读到的内容行；已到流末尾且未读到任何字符时返回 {@code null}
     * @throws IOException 读取过程中发生 I/O 错误
     */
    private static Line readLine(PushbackReader reader, boolean firstLine) throws IOException {
        StringBuilder text = new StringBuilder();
        boolean seen = false;
        boolean truncated = false;
        int value;
        while ((value = reader.read()) != -1) {
            seen = true;
            if (value == '\n') {
                break;
            }
            if (value == '\r') {
                int next = reader.read();
                if (next != '\n' && next != -1) {
                    reader.unread(next);
                }
                break;
            }
            if (firstLine && text.length() == 0 && value == '\uFEFF') {
                continue;
            }
            if (text.length() < MAX_LINE_CHARS) {
                text.append((char) value);
            } else {
                truncated = true;
            }
        }
        return !seen ? null : new Line(text.toString(), truncated);
    }

    /**
     * 单行读取结果：截断后的文本 + 是否发生过截断的标记。
     *
     * @param text      截断至最多 {@value #MAX_LINE_CHARS} 字符的行内容（不含换行符）
     * @param truncated 是否因超过 {@value #MAX_LINE_CHARS} 而丢弃了尾部内容
     */
    private static final class Line {
        /** 截断至最多 {@value #MAX_LINE_CHARS} 字符的行内容（不含换行符）。 */
        final String text;
        /** 是否因超过 {@value #MAX_LINE_CHARS} 而丢弃了尾部内容。 */
        final boolean truncated;

        Line(String text, boolean truncated) {
            this.text = text;
            this.truncated = truncated;
        }
    }
}
