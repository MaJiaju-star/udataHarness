package org.noear.solon.ai.talents.cli;

import com.udata.harness.common.context.ContextArtifactStore;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.noear.solon.annotation.Param;
import org.noear.solon.ai.talents.mount.MountManager;
import org.noear.solon.ai.talents.mount.ResolvedResource;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Bounded replacement for the Solon 4.1.1 read tool. It deliberately reuses the
 * engine's TerminalSupport so mount, symlink and mandatory-deny checks stay in
 * one place. If that upstream private field changes, construction fails closed.
 *
 * <p>读取行为受四重上限约束，避免单次工具结果撑爆上下文：</p>
 * <ul>
 *   <li>默认返回前 {@value #DEFAULT_LINES} 行，单次最多 {@value #MAX_LINES} 行；</li>
 *   <li>单次编号正文不超过 {@value #MAX_OUTPUT_BYTES} 字节（256 KiB）；</li>
 *   <li>超长单行只保留前 {@value #MAX_LINE_CHARS} 字符；</li>
 *   <li>到达任一上限时在结果尾部给出续读所需的 offset/limit。</li>
 * </ul>
 *
 * <p>分页由调用方通过 {@code offset} 参数驱动。仅为相同会话、相同范围、
 * 内容哈希未变化的重复读取保留有界的快照索引；不同会话不可复用；索引仅复用归档件，不替代读取正文。</p>
 *
 * @see #read(String, Integer, Integer, String)
 * @see #readWindow(Path, String, int, int, long)
 */
public final class BoundedReadTool extends AbsTalent {
    /** 默认返回行数（未显式传 limit 时生效）。 */
    static final int DEFAULT_LINES = 4000;
    static final int LARGE_FILE_PREVIEW_LINES = 400;
    static final long LARGE_FILE_THRESHOLD_BYTES = 32L * 1024;
    /** 单次读取行数硬上限（传入更大的 limit 会被截断到该值）。 */
    static final int MAX_LINES = 8000;
    static final int MAX_FOCUS_POINTS = 8;
    static final int MAX_FOCUS_TOTAL_LINES = 1600;
    /** 单行显示字符数上限；超出部分截断并标记 {@code …(单行已截断)}。 */
    static final int MAX_LINE_CHARS = 64 * 1024;
    /** 单次编号正文的 UTF-8 字节上限（256 KiB，含行号，不含页眉和提示）。 */
    static final int MAX_OUTPUT_BYTES = 256 * 1024;
    /** Historical result pages stay small enough for targeted follow-up reads. */
    static final int SNAPSHOT_OUTPUT_BYTES = 16 * 1024;
    static final int SNAPSHOT_CHAR_LIMIT = 16_000;
    static final long MAX_VERSIONED_BYTES = 16L * 1024 * 1024;
    private static final int MAX_INDEX_ENTRIES = 512;

    private final TerminalTalent terminal;
    private final TerminalSupport support;
    private final MountManager mountManager;
    private final Field warmupHook;
    private final Path workspace;
    private final ToolResultPaths toolResults;
    private final ContextArtifactStore artifactStore;
    private final SessionFileVersions fileVersions;
    private final Map<String, CachedRead> snapshots = new LinkedHashMap<>(64, .75f, true);

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
        this(terminal, workspace, null);
    }

    public BoundedReadTool(TerminalTalent terminal, String workspace, ContextArtifactStore store) {
        this(terminal, workspace, store, new SessionFileVersions());
    }

    /**
     * 创建与修改工具共享会话版本记录的读取工具。
     *
     * @param terminal 宿主终端工具
     * @param workspace 默认工作目录
     * @param store 历史快照仓储，可为 null
     * @param fileVersions 当前工作区的会话版本记录
     */
    public BoundedReadTool(TerminalTalent terminal, String workspace, ContextArtifactStore store,
                           SessionFileVersions fileVersions) {
        this.terminal = terminal;
        this.workspace = Paths.get(workspace).toAbsolutePath().normalize();
        this.toolResults = new ToolResultPaths(store);
        this.artifactStore = store;
        this.fileVersions = fileVersions;
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
        return "read 默认读取小文件前 4000 行；大文件预览前 400 行。局部查看用 focus_line；同一文件多个位置用 focus_lines（最多 8 点，重叠窗口合并）。通读可显式传 limit。@tool-results 是只读历史快照。";
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
     * @return 正文含文件窗口、续读提示或错误说明；版本仅放入内部元数据
     * @throws IOException        读取过程中发生 I/O 错误
     * @throws IllegalArgumentException {@code filePath} 为 null
     */
    @ToolMapping(name = "read", description = "读取文本文件。大文件默认预览前 400 行；单处用 focus_line，多处用 focus_lines 一次读取并合并重叠窗口；通读时显式设置 limit（最多 8000 行、256 KiB）。")
    public ToolResult readResult(
            @Param(value = "file_path", description = "工作区相对路径或 @ 开头的逻辑路径") String filePath,
            @Param(value = "offset", required = false, description = "起始行号，从 1 开始；@tool-results 路径按快照物理行计数，不是原文件行号") Integer offset,
            @Param(value = "limit", required = false, description = "最多读取的行数；默认小文件 4000 行、大文件预览 400 行，显式指定时最多 8000 行") Integer limit,
            @Param(value = "char_offset", required = false, description = "仅 @tool-results 使用：从此 UTF-16 字符位置分页") Integer charOffset,
            @Param(value = "char_limit", required = false, description = "仅 @tool-results 使用：最多读取字符数，上限 16000") Integer charLimit,
            @Param(value = "focus_line", required = false, description = "原文件中的目标行号；返回该行附近代码，不可与 offset/limit 同用") Integer focusLine,
            @Param(value = "focus_lines", required = false, description = "同一原文件的多个目标行号，最多 8 个；重叠窗口合并，不可与 focus_line/offset/limit 同用") List<Integer> focusLines,
            @Param(value = "context_lines", required = false, description = "每个焦点前后各读取的行数；单点默认 80，多点默认 40，最多 1000") Integer contextLines,
            String __cwd, String __sessionId) throws IOException {
        Map<String, Object> metadata = new LinkedHashMap<>();
        String content = readBody(filePath, offset, limit, charOffset, charLimit,
                focusLine, focusLines, contextLines, __cwd, __sessionId, metadata);
        ToolResult result = ToolResult.success(content);
        result.metas().putAll(metadata);
        return result;
    }

    /** 保留直接调用入口，内部版本元数据不进入返回正文。 */
    public String read(String filePath, Integer offset, Integer limit, Integer charOffset,
                       Integer charLimit, String __cwd, String __sessionId) throws IOException {
        return readResult(filePath, offset, limit, charOffset, charLimit,
                null, null, null, __cwd, __sessionId).getContent();
    }

    /** Keep the existing single-focus Java signature. */
    public ToolResult readResult(String filePath, Integer offset, Integer limit, Integer charOffset,
                                 Integer charLimit, Integer focusLine, Integer contextLines,
                                 String __cwd, String __sessionId) throws IOException {
        return readResult(filePath, offset, limit, charOffset, charLimit,
                focusLine, null, contextLines, __cwd, __sessionId);
    }

    /** Compatibility entry point for existing callers; focus options are model-facing. */
    public ToolResult readResult(String filePath, Integer offset, Integer limit, Integer charOffset,
                                 Integer charLimit, String __cwd, String __sessionId) throws IOException {
        return readResult(filePath, offset, limit, charOffset, charLimit,
                null, null, null, __cwd, __sessionId);
    }

    /** 执行有界读取，并把本次精确版本写入仅供后端使用的元数据。 */
    private String readBody(String filePath, Integer offset, Integer limit, Integer charOffset,
                            Integer charLimit, Integer focusLine, List<Integer> focusLines, Integer contextLines,
                            String __cwd, String __sessionId,
                            Map<String, Object> metadata) throws IOException {
        if (filePath == null) {
            throw new IllegalArgumentException("file_path 不能为空");
        }
        boolean multiFocus = focusLines != null;
        if (focusLine != null && multiFocus) return "错误：focus_line 与 focus_lines 不能同时使用。";
        if ((focusLine != null || multiFocus)
                && (offset != null || limit != null || charOffset != null || charLimit != null)) {
            return "错误：focus_line/focus_lines 不能与 offset/limit 或快照字符分页参数同用。";
        }
        if (contextLines != null && focusLine == null && !multiFocus) {
            return "错误：context_lines 需要 focus_line 或 focus_lines。";
        }
        if (focusLine != null && focusLine < 1) return "错误：focus_line 必须从 1 开始。";
        if (multiFocus && (focusLines.isEmpty() || focusLines.size() > MAX_FOCUS_POINTS)) {
            return "错误：focus_lines 需要 1～8 个原文件行号。";
        }
        int radius = contextLines == null ? multiFocus ? 40 : 80 : Math.max(0, Math.min(contextLines, 1000));
        List<Integer> anchors = null;
        List<FocusWindow> windows = null;
        if (multiFocus) {
            TreeSet<Integer> unique = new TreeSet<>();
            for (Integer point : focusLines) {
                if (point == null || point < 1) return "错误：focus_lines 中每个行号都必须从 1 开始。";
                unique.add(point);
            }
            anchors = new ArrayList<>(unique);
            windows = focusWindows(anchors, radius);
            long total = 0;
            for (FocusWindow window : windows) total += (long) window.end - window.start + 1;
            if (total > MAX_FOCUS_TOTAL_LINES) {
                return "错误：合并后的焦点窗口超过 1600 行；请减少 context_lines 或分批读取。";
            }
        }
        int start = focusLine == null ? offset == null || offset < 1 ? 1 : offset
                : Math.max(1, focusLine - radius);
        int count = focusLine == null ? limit == null || limit < 1 ? DEFAULT_LINES : Math.min(limit, MAX_LINES)
                : Math.min(MAX_LINES, radius * 2 + 1);

        if (ToolResultPaths.isVirtual(filePath)) {
            if (focusLine != null || multiFocus) return "错误：focus_line/focus_lines 仅适用于当前工作区文件；历史快照请使用 offset/limit。";
            try {
                Path snapshot = toolResults.resolve(filePath, __sessionId, false);
                String id = filePath.substring(ToolResultPaths.ROOT.length() + 1, filePath.length() - 4);
                String header = "历史工具结果快照（只读，不代表当前工作区文件）。\n"
                        + "正文中的原文件行号和原工具续读提示保持原样，不是快照分页位置；快照分页参数也不能用于原文件。\n"
                        + (artifactStore.load(__sessionId, id).captureComplete ? "" : "注意：原命令捕获不完整，此快照只包含已捕获部分。\n");
                if (charOffset != null || charLimit != null) {
                    int requested = charLimit == null ? 12000 : Math.min(charLimit, SNAPSHOT_CHAR_LIMIT);
                    if (charOffset != null && charOffset < 0 || requested < 1) {
                        return "错误：char_offset 必须非负，char_limit 必须为正数。";
                    }
                    return header + "当前为快照字符分页：offset/next_offset 元信息的单位是快照 UTF-16 字符，不是行号。\n"
                            + artifactStore.restore(__sessionId, id, charOffset == null ? 0 : charOffset, requested);
                }
                return header + readSnapshotWindow(snapshot, filePath, start, count);
            } catch (IOException e) {
                return "错误：当前会话中找不到该工具结果，或路径无效。";
            }
        }

        if (charOffset != null || charLimit != null) return "错误：char_offset/char_limit 仅适用于 @tool-results。";

        // Non-local virtual sources stay on Solon's MountSource read path and its
        // 10 MB guard. Local mounts use the same bounded scanner as workspace files.
        if (filePath.startsWith("@")) {
            ResolvedResource resource = mountManager.resolveResource(filePath);
            if (!resource.getSource().capabilities().isLocalPathAccessible()
                    || !resource.getLocalPath().isPresent()) {
                if (multiFocus) return "错误：focus_lines 需要可本地读取的文件；该逻辑挂载请分次读取。";
                return terminal.read(filePath, start, count, __cwd);
            }
        }

        Path workPath = __cwd == null ? workspace : Paths.get(__cwd).toAbsolutePath().normalize();
        Path target = support.resolveSafePath(workPath, filePath, false,
                terminal.isSandboxEnabled(), false, null);
        com.udata.harness.repository.ModelConfigurationFiles.requireAllowed(target);
        if (!Files.exists(target)) {
            fileVersions.remember(__sessionId, target, null);
            return "错误：文件不存在";
        }
        if (!Files.isRegularFile(target)) {
            return "错误：目标不是普通文件";
        }
        if (support.isNotTextFile(target)) {
            return "错误：该文件是二进制格式，无法作为文本读取。";
        }
        long fileSize = Files.size(target);
        boolean automaticPreview = focusLine == null && !multiFocus && offset == null && limit == null
                && fileSize > LARGE_FILE_THRESHOLD_BYTES;
        if (automaticPreview) count = LARGE_FILE_PREVIEW_LINES;
        //1. 按会话、文件真实路径和归一化后的读取范围查找上次结果。
        String beforeVersion = fileSize <= MAX_VERSIONED_BYTES ? FileVersion.sha256(target) : null;
        String cacheKey = beforeVersion == null || artifactStore == null || __sessionId == null
                || __sessionId.isEmpty() ? null
                : __sessionId + '\0' + target.toRealPath() + '\0'
                + (multiFocus ? "focus:" + anchors + ":" + radius : start + "\0" + count);
        CachedRead cached = null;
        if (cacheKey != null) {
            synchronized (snapshots) { cached = snapshots.get(cacheKey); }
        }
        // The tool cannot know which old messages remain model-visible. Always return
        // current body here; only the context interceptor may replace it with a reference.
        //2. 每次读取最新正文，并检查读取期间的变化。
        String output = fileSize == 0 ? "(文件内容为空)"
                : multiFocus ? readFocusWindows(target, filePath, windows, anchors, radius, fileSize)
                : readWindow(target, filePath, start, count, fileSize);
        if (automaticPreview && !output.startsWith("错误：")) {
            String total = fileSize <= MAX_VERSIONED_BYTES
                    ? String.valueOf(countLines(target)) : "未统计（文件超过 16 MiB）";
            output = "[大文件预览：总行数 " + total
                    + "；仅展示前 400 行。通读可用 read(file_path, limit=所需行数)，最多 8000 行/256 KiB；定位后用 focus_line 或 offset/limit]\n"
                    + output;
        }
        if (beforeVersion != null) {
            if (!beforeVersion.equals(FileVersion.sha256(target))) {
                return "错误：文件在读取期间发生变化，请重新读取。";
            }
        }
        if (output.startsWith("错误：")) return output;
        if (beforeVersion != null) metadata.put("sourceHash", beforeVersion);
        fileVersions.remember(__sessionId, target, beforeVersion);
        warmup(target);
        //3. 保留可恢复的历史快照；已存在的同版本快照无需重复归档。
        if (cacheKey != null) {
            try {
                if (cached != null && beforeVersion.equals(cached.version)) {
                    try {
                        artifactStore.load(__sessionId, cached.artifactId);
                        metadata.put("readSnapshotId", cached.artifactId);
                        return output;
                    } catch (IOException ignored) {
                        synchronized (snapshots) { snapshots.remove(cacheKey); }
                    }
                }
                String invocation = "read-cache:v2:" + UUID.randomUUID();
                ContextArtifactStore.Artifact artifact = artifactStore.save(
                        __sessionId, invocation, "read", output, "unchanged_read_reference", 0);
                metadata.put("readSnapshotId", artifact.id);
                synchronized (snapshots) {
                    snapshots.put(cacheKey, new CachedRead(beforeVersion, artifact.id));
                    if (snapshots.size() > MAX_INDEX_ENTRIES) {
                        snapshots.remove(snapshots.keySet().iterator().next());
                    }
                }
            } catch (IOException ignored) {
                // No recoverable snapshot means no short reference; keep the complete result.
            }
        }
        return output;
    }

    /** Count line endings without materializing the file or assuming LF-only source. */
    private static long countLines(Path path) throws IOException {
        long lines = 0;
        boolean sawByte = false, previousCr = false, endedInBreak = false;
        try (InputStream input = new BufferedInputStream(Files.newInputStream(path))) {
            int value;
            while ((value = input.read()) != -1) {
                sawByte = true;
                if (value == '\r') {
                    lines++;
                    previousCr = true;
                    endedInBreak = true;
                } else if (value == '\n') {
                    if (!previousCr) lines++;
                    previousCr = false;
                    endedInBreak = true;
                } else {
                    previousCr = false;
                    endedInBreak = false;
                }
            }
        }
        return lines + (sawByte && !endedInBreak ? 1 : 0);
    }

    private static List<FocusWindow> focusWindows(List<Integer> anchors, int radius) {
        List<FocusWindow> windows = new ArrayList<>();
        for (Integer point : anchors) {
            int start = Math.max(1, point - radius);
            int end = (int) Math.min(Integer.MAX_VALUE, (long) point + radius);
            if (!windows.isEmpty() && start <= (long) windows.get(windows.size() - 1).end + 1) {
                FocusWindow previous = windows.get(windows.size() - 1);
                previous.end = Math.max(previous.end, end);
            } else {
                windows.add(new FocusWindow(start, end));
            }
        }
        return windows;
    }

    /** Scan the source once, returning only the merged, numbered focus windows. */
    private static String readFocusWindows(Path target, String displayPath, List<FocusWindow> windows,
                                           List<Integer> anchors, int radius, long fileSize) throws IOException {
        StringBuilder body = new StringBuilder();
        int outputBytes = 0;
        long lineNumber = 1, lastScanned = 0, lastShown = 0;
        int windowIndex = 0, headingIndex = -1;
        boolean reachedEof = false, byteLimit = false, longLine = false;
        try (PushbackReader reader = new PushbackReader(
                new BufferedReader(Files.newBufferedReader(target, StandardCharsets.UTF_8), 8192), 1)) {
            while (windowIndex < windows.size()) {
                FocusWindow window = windows.get(windowIndex);
                if (lineNumber > window.end) { windowIndex++; continue; }
                Line line = readLine(reader, lineNumber == 1);
                if (line == null) { reachedEof = true; break; }
                lastScanned = lineNumber;
                if (lineNumber < window.start) { lineNumber++; continue; }
                String heading = headingIndex == windowIndex ? ""
                        : "--- 原文件行 " + window.start + "-" + window.end + " ---\n";
                String formatted = String.format("%6d | %s%s\n", lineNumber, line.text,
                        line.truncated ? "…(单行已截断)" : "");
                int bytes = (heading + formatted).getBytes(StandardCharsets.UTF_8).length;
                if (outputBytes + bytes > MAX_OUTPUT_BYTES) { byteLimit = true; break; }
                body.append(heading).append(formatted);
                outputBytes += bytes;
                headingIndex = windowIndex;
                longLine |= line.truncated;
                lastShown = lineNumber;
                lineNumber++;
            }
        }
        if (lastShown == 0 && reachedEof) return "错误：所有 focus_lines 均超出文件范围。";
        StringBuilder result = new StringBuilder("--- File: ").append(displayPath)
                .append(" (Focus lines: ").append(anchors).append(", Context: ").append(radius)
                .append(", Size: ").append(String.format("%.2f KB", fileSize / 1024.0))
                .append(") ---\n").append(body);
        if (longLine) result.append("提示：超长单行仅显示前 ").append(MAX_LINE_CHARS)
                .append(" 字符；该行剩余内容未返回。\n");
        if (byteLimit) result.append("--- [焦点结果不完整：正文达到 256 KiB 上限] ---\n")
                .append("请缩小 context_lines 或分批传 focus_lines；当前位置可用 offset=")
                .append(lastShown + 1).append(" 续读。\n");
        else result.append("--- [已返回请求的焦点窗口；未展示窗口间的行] ---\n");
        if (reachedEof) {
            List<Integer> missing = new ArrayList<>();
            for (Integer anchor : anchors) if (anchor > lastScanned) missing.add(anchor);
            if (!missing.isEmpty()) result.append("超出文件范围的焦点行：").append(missing).append("。\n");
        }
        return result.toString();
    }

    private static final class FocusWindow {
        final int start;
        int end;
        FocusWindow(int start, int end) { this.start = start; this.end = end; }
    }

    private static final class CachedRead {
        final String version;
        final String artifactId;

        CachedRead(String version, String artifactId) {
            this.version = version;
            this.artifactId = artifactId;
        }
    }

    /** Source-compatible entry point for direct callers; virtual results require injected session ID. */
    public String read(String filePath, Integer offset, Integer limit, String __cwd) throws IOException {
        return read(filePath, offset, limit, null, null, __cwd, null);
    }

    public String read(String filePath, Integer offset, Integer limit, String __cwd,
                       String __sessionId) throws IOException {
        return read(filePath, offset, limit, null, null, __cwd, __sessionId);
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
     * 按快照物理行分页返回原文，不再次编号或归一化换行；字节上限截断时用字符游标续读。
     *
     * @param target 已通过会话校验的快照文件
     * @param path 快照逻辑路径
     * @param offset 快照起始物理行，从 1 开始
     * @param limit 最多读取的快照物理行数
     * @return 原样正文、快照范围说明及明确指向同一快照的续读指令
     * @throws IOException 快照读取失败
     */
    private static String readSnapshotWindow(Path target, String path, int offset, int limit) throws IOException {
        StringBuilder body = new StringBuilder();
        long position = 0;
        int line = 1, lastLine = offset - 1, completed = 0, bytes = 0;
        boolean capped = false, hasMore = false;
        try (PushbackReader reader = new PushbackReader(Files.newBufferedReader(target, StandardCharsets.UTF_8), 1)) {
            //1. 按物理行跳过正文，同时记录精确 UTF-16 位置，兼容 CRLF、LF 和 CR。
            while (line < offset) {
                int value = reader.read();
                if (value == -1) return "错误：快照起始物理行 (" + offset + ") 已超出快照范围。";
                position++;
                if (value == '\r') {
                    int next = reader.read();
                    if (next == '\n') position++;
                    else if (next != -1) reader.unread(next);
                    line++;
                } else if (value == '\n') line++;
            }
            long startPosition = position;
            //2. 保留原字符和行终止符，不拆开代理对或 CRLF，不丢弃超长行的剩余内容。
            while (completed < limit) {
                String unit = readSnapshotUnit(reader);
                if (unit == null) break;
                int size = unit.getBytes(StandardCharsets.UTF_8).length;
                if (bytes + size > SNAPSHOT_OUTPUT_BYTES) {
                    capped = true;
                    hasMore = true;
                    break;
                }
                body.append(unit);
                bytes += size;
                position += unit.length();
                lastLine = line;
                if (unit.endsWith("\n") || unit.endsWith("\r")) { completed++; line++; }
            }
            if (!hasMore) hasMore = reader.read() != -1;
            if (body.length() == 0 && offset > 1) {
                return "错误：快照起始物理行 (" + offset + ") 已超出快照范围。";
            }
            //3. 分页元信息和正文分开，所有续读指令都显式携带快照路径。
            String paging = (body.length() == 0 ? "快照范围：空正文" : "快照范围：物理行 " + offset + "-" + lastLine)
                    + "；UTF-16 字符区间 [" + startPosition + ", " + position + ")。\n";
            String continuation = !hasMore ? "快照本次回读已到末尾；不表示原文件已读取完毕。"
                    : capped ? "快照正文达到单次字节上限，剩余内容未丢弃；快照字符续读（不是原文件续读）：read(file_path=\""
                    + path + "\", char_offset=" + position + ", char_limit=12000)"
                    : "快照行续读（不是原文件续读）：read(file_path=\"" + path
                    + "\", offset=" + line + ", limit=" + limit + ")";
            return paging + "<snapshot-content>\n" + body + "\n</snapshot-content>\n" + continuation;
        }
    }

    /**
     * 读取一个完整字符或 CRLF 行终止符，不改动快照原文。
     *
     * @param reader 快照字符流
     * @return 原字符片段；到达末尾时返回 null
     * @throws IOException 读取失败
     */
    private static String readSnapshotUnit(PushbackReader reader) throws IOException {
        int value = reader.read();
        if (value == -1) return null;
        String unit = String.valueOf((char) value);
        if (value == '\r' || Character.isHighSurrogate((char) value)) {
            int next = reader.read();
            if (next != -1) {
                if (value == '\r' && next == '\n' || Character.isHighSurrogate((char) value)
                        && Character.isLowSurrogate((char) next)) unit += (char) next;
                else reader.unread(next);
            }
        }
        return unit;
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
        return readWindow(target, displayPath, offset, limit, fileSize, MAX_OUTPUT_BYTES);
    }

    static String readWindow(Path target, String displayPath, int offset, int limit, long fileSize,
                             int outputBudget) throws IOException {
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
                if (outputBytes + bytes > outputBudget) {
                    if (emitted == 0) {
                        String prefix = String.format("%6d | ", lineNumber);
                        String marker = "…(单行已截断)\n";
                        int allowance = outputBudget - prefix.getBytes(StandardCharsets.UTF_8).length
                                - marker.getBytes(StandardCharsets.UTF_8).length;
                        body.append(prefix).append(fitUtf8(line.text, Math.max(0, allowance))).append(marker);
                        longLine = true;
                        lastLine = lineNumber;
                        emitted++;
                    }
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

    private static String fitUtf8(String value, int byteBudget) {
        int end = 0;
        int bytes = 0;
        while (end < value.length()) {
            int codePoint = value.codePointAt(end);
            int width = codePoint <= 0x7f ? 1 : codePoint <= 0x7ff ? 2
                    : codePoint <= 0xffff ? 3 : 4;
            if (bytes + width > byteBudget) break;
            bytes += width;
            end += Character.charCount(codePoint);
        }
        return value.substring(0, end);
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
     */
    private static final class Line {
        /** 截断至最多 {@value #MAX_LINE_CHARS} 字符的行内容（不含换行符）。 */
        final String text;
        /** 是否因超过 {@value #MAX_LINE_CHARS} 而丢弃了尾部内容。 */
        final boolean truncated;

        /**
         * @param text      截断至最多 {@value #MAX_LINE_CHARS} 字符的行内容（不含换行符）
         * @param truncated 是否因超过 {@value #MAX_LINE_CHARS} 而丢弃了尾部内容
         */
        Line(String text, boolean truncated) {
            this.text = text;
            this.truncated = truncated;
        }
    }
}
