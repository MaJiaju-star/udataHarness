package org.noear.solon.ai.talents.cli;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.ai.talents.mount.MountManager;
import org.noear.solon.ai.talents.mount.ResolvedResource;
import org.noear.solon.annotation.Param;

import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 有界的、按字典序分页的本地目录列举工具。
 *
 * <p>与 Solon 上游 {@code TerminalTalent#ls} 的全量列举不同，本工具对返回量施加三重上限约束：
 * <ul>
 *   <li>条数上限：默认 {@value #DEFAULT_LIMIT} 项，单页最多 {@value #MAX_LIMIT} 项；</li>
 *   <li>字符上限：单次输出最多 {@value #MAX_OUTPUT_CHARS} 字符（含行前缀与页眉页脚）；</li>
 *   <li>深度上限：{@code recursive=true} 时最多遍历 3 层目录。</li>
 * </ul>
 *
 * <p>分页采用<b>字典序游标</b>（{@code after}）而非偏移量：每次调用按相对路径升序取一页，
 * 输出末尾携带 {@code after=<最后一条>} 游标，模型据此续取下一页。游标与遍历顺序解耦，
 * 因此多次调用可安全交错（如先列 A 目录一页、再列 B 目录一页）。
 *
 * <p>路径处理复用 {@link TerminalSupport} 的安全守卫（忽略规则、沙箱读策略、逻辑路径挂载），
 * 通过反射从 {@link TerminalTalent} 取得，与 {@link BoundedReadTool} 采取同一策略：
 * 快速失败而非静默降级为不安全路径。
 *
 * @see BoundedReadTool
 * @see TerminalTalent
 */
public final class BoundedLsTool extends AbsTalent {
    /** 默认单页条数（模型未传 limit 或 limit < 1 时生效）。 */
    private static final int DEFAULT_LIMIT = 100;
    /** 单页条数硬上限（即使模型传入更大 limit 也会被截断到此值）。 */
    private static final int MAX_LIMIT = 500;
    /** 单次输出的字符硬上限，含 "[DIR] "/"[FILE] " 前缀与结尾游标提示。 */
    private static final int MAX_OUTPUT_CHARS = 50_000;

    /** 上游终端 Talent，用于反射取守卫对象，以及回落委托不可访问的逻辑路径列举。 */
    private final TerminalTalent terminal;
    /** Solon 路径安全守卫（resolveSafePath/isIgnored/isReadDenied）。 */
    private final TerminalSupport support;
    /** 逻辑路径（@xxx）挂载解析器。 */
    private final MountManager mounts;
    /** 工作区根目录（已绝对路径化并 normalize）。 */
    private final Path workspace;

    /**
     * 构造工具实例。
     *
     * <p>通过反射复用 {@code TerminalTalent} 的私有 {@code support} 与 {@code mountManager} 字段，
     * 以共享上游同一套路径安全策略；这属于复用而非绕过——若字段名变更导致反射失败，
     * 会立即抛出 {@link IllegalStateException}（fail-closed），不会退化为无守卫的路径访问。
     *
     * @param terminal  上游终端 Talent，提供安全守卫与逻辑路径能力
     * @param workspace 工作区根目录字符串，构造时绝对路径化并 normalize
     * @throws IllegalStateException 反射取得守卫对象失败（上游字段被改名或不可访问）
     */
    public BoundedLsTool(TerminalTalent terminal, String workspace) {
        this.terminal = terminal;
        this.workspace = Paths.get(workspace).toAbsolutePath().normalize();
        try {
            Field supportField = TerminalTalent.class.getDeclaredField("support");
            supportField.setAccessible(true);
            this.support = (TerminalSupport) supportField.get(terminal);
            Field mountField = TerminalTalent.class.getDeclaredField("mountManager");
            mountField.setAccessible(true);
            this.mounts = (MountManager) mountField.get(terminal);
        } catch (ReflectiveOperationException | SecurityException e) {
            throw new IllegalStateException("Solon listing path guard is unavailable", e);
        }
    }

    /**
     * 提供给模型的使用提示：覆盖 Solon 默认的目录列举说明，
     * 强调 100 项默认页与 {@code after} 游标续页机制，避免模型误以为一次可拿全量结果。
     *
     * @param prompt 当前提示词上下文（未使用）
     * @return 中文使用指令
     */
    @Override
    public String getInstruction(Prompt prompt) {
        return "ls 默认只返回 100 项。若有更多结果，按输出的 after 游标继续；recursive 最多遍历三层。";
    }

    /**
     * 工具入口：列出目录条目（字典序分页）。
     *
     * <p>处理流程：
     * <ol>
     *   <li>逻辑路径（{@code @xxx}）：若挂载来源不可本地访问，回落委托 {@link TerminalTalent#ls} 处理；</li>
     *   <li>参数归一：{@code after} 游标长度校验（&le; 4096 字符）、{@code limit} 钳制到 [1, {@value #MAX_LIMIT}]；</li>
     *   <li>路径校验：经 {@code resolveSafePath} 取得安全路径，不存在或非目录返回错误字符串；</li>
     *   <li>遍历收集：{@link Files#walkFileTree} 按深度上限（1 或 3 层）遍历，
     *       应用忽略规则、沙箱读策略与隐藏文件过滤后，用容量为 {@code pageSize+1} 的
     *       最大堆维持"字典序最小的 pageSize+1 条"；</li>
     *   <li>出页渲染：排序、截断到最后一条，拼接输出并在末尾附游标提示。</li>
     * </ol>
     *
     * <p>所有错误（路径不存在、非目录、游标过长）均返回字符串而非抛异常，
     * 以便模型读到错误文本后自行修正参数重试。
     *
     * @param path         目录相对路径或逻辑路径；null/空表示当前工作区（{@code .}）
     * @param recursive    是否递归，true 时最多遍历三层；null 视为 false
     * @param showHidden   是否包含以 {@code .} 开头的条目；null 视为 false
     * @param after        上一页返回的 {@code after} 游标，仅列出字典序严格大于它的条目；
     *                     超过 4096 字符直接拒绝
     * @param limit        单页条数，默认 {@value #DEFAULT_LIMIT}，钳制上限 {@value #MAX_LIMIT}
     * @param __cwd        当前目录（Solon 注入）；null 时使用工作区根
     * @return 格式化列表（{@code [DIR]}/{@code [FILE]} 前缀）+ 页尾状态与续页游标；或错误字符串
     * @throws IOException 遍历文件树时发生 I/O 错误（调用方捕获转为会话错误）
     */
    @ToolMapping(name = "ls", description = "列出目录。默认 100 项、最多 500 项；内容未完时使用 after 游标继续。recursive 最多遍历三层；支持逻辑路径。")
    public String ls(
            @Param(value = "path", required = false, description = "目录相对路径或逻辑路径，默认当前工作区") String path,
            @Param(value = "recursive", required = false, description = "是否递归列出，最多三层") Boolean recursive,
            @Param(value = "show_hidden", required = false, description = "是否显示隐藏文件") Boolean showHidden,
            @Param(value = "after", required = false, description = "上一页返回的 after 游标") String after,
            @Param(value = "limit", required = false, description = "最多返回条数，默认 100，最大 500") Integer limit,
            String __cwd) throws IOException {
        String selectedPath = path == null || path.isEmpty() ? "." : path;
        if (selectedPath.startsWith("@")) {
            ResolvedResource resource = mounts.resolveResource(selectedPath);
            if (!resource.getSource().capabilities().isLocalPathAccessible()
                    || !resource.getLocalPath().isPresent()) {
                return terminal.ls(selectedPath, recursive, showHidden, __cwd);
            }
        }
        if (after != null && after.length() > 4096) return "错误：after 游标过长。";
        int pageSize = limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        Path workPath = __cwd == null ? workspace : Paths.get(__cwd).toAbsolutePath().normalize();
        Path target = support.resolveSafePath(workPath, selectedPath, false,
                terminal.isSandboxEnabled(), false, null);
        if (!Files.exists(target)) return "错误：路径不存在";
        if (!Files.isDirectory(target)) return "错误：目标不是目录";
        Path policyRoot = support.getSandboxPolicyRoot(workPath, selectedPath);
        PriorityQueue<Entry> page = new PriorityQueue<>(Comparator.comparing((Entry entry) -> entry.key).reversed());
        boolean includeHidden = Boolean.TRUE.equals(showHidden);
        int maxDepth = Boolean.TRUE.equals(recursive) ? 3 : 1;
        Files.walkFileTree(target, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), maxDepth,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                        if (directory.equals(target)) return FileVisitResult.CONTINUE;
                        if (filtered(directory)) return FileVisitResult.SKIP_SUBTREE;
                        offer(directory, true);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (!filtered(file)) offer(file, attrs.isDirectory());
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException error) {
                        return FileVisitResult.CONTINUE;
                    }

                    private boolean filtered(Path item) {
                        return support.isIgnored(workPath, item)
                                || support.isReadDenied(policyRoot, item, terminal.isSandboxEnabled(), null)
                                || (!includeHidden && item.getFileName().toString().startsWith("."));
                    }

                    private void offer(Path item, boolean directory) {
                        String key = target.relativize(item).toString().replace('\\', '/');
                        if (after != null && key.compareTo(after) <= 0) return;
                        page.offer(new Entry(key, directory));
                        if (page.size() > pageSize + 1) page.poll();
                    }
                });
        if (page.isEmpty()) return after == null ? "(目录为空)" : "(没有更多目录项)";
        List<Entry> ordered = new ArrayList<>(page);
        ordered.sort(Comparator.comparing(entry -> entry.key));
        boolean hasMore = ordered.size() > pageSize;
        if (hasMore) ordered.remove(ordered.size() - 1);
        String root = ".".equals(selectedPath) ? "" : selectedPath.replace('\\', '/').replaceAll("/+$", "") + "/";
        StringBuilder output = new StringBuilder();
        String lastKey = null;
        for (Entry entry : ordered) {
            String line = (entry.directory ? "[DIR] " : "[FILE] ")
                    + root + entry.key + (entry.directory ? "/" : "") + "\n";
            if (output.length() + line.length() > MAX_OUTPUT_CHARS) {
                hasMore = true;
                break;
            }
            output.append(line);
            lastKey = entry.key;
        }
        if (lastKey == null) return "错误：目录项路径过长，无法在输出上限内显示。";
        if (hasMore) {
            output.append("--- [内容未完] ---\n继续列出请使用 after=")
                    .append(lastKey)
                    .append(", limit=").append(pageSize);
        } else {
            output.append("--- [目录列举完毕] ---");
        }
        return output.toString();
    }

    /**
     * 分页堆中的单个目录条目。
     *
     * <p>堆排序仅依赖 {@link #key}（相对路径，已统一为 {@code /} 分隔），
     * {@link #directory} 只参与渲染。
     */
    private static final class Entry {
        /** 相对目标目录的字典序键（反斜杠已替换为 {@code /}），充当游标与排序键。 */
        final String key;
        /** 是否为目录（决定 {@code [DIR]} / {@code [FILE]} 前缀与尾部斜杠）。 */
        final boolean directory;

        /**
         * 构造条目。
         *
         * @param key       字典序排序键（相对路径）
         * @param directory 是否为目录
         */
        Entry(String key, boolean directory) {
            this.key = key;
            this.directory = directory;
        }
    }
}
