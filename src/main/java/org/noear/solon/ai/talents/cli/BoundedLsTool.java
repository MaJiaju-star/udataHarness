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
 * <p>与 Solon 上游 {@code TerminalTalent#ls} 的全量列举不同，本工具对返回量施加上限约束：
 * <ul>
 *   <li>条数上限：默认 {@value #DEFAULT_LIMIT} 项，单页最多 {@value #MAX_LIMIT} 项；</li>
 *   <li>字符上限：单次输出最多 {@value #MAX_OUTPUT_CHARS} 字符（含行前缀与页眉页脚）；</li>
 *   <li>递归默认遍历全部层级；可用 {@code max_depth} 显式限制深度，未展开时明确提示。</li>
 * </ul>
 *
 * <p>默认 auto 模式先列直属目录、再列直属文件；每个小目录最多向下探查两层，
 * 大目录仅返回有界概览。显式递归和 flat 模式继续使用原路径字典序游标。
 * 所有模式输出末尾都携带当前模式专属的 {@code after} 续页游标。
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
    private static final int DEFAULT_LIMIT = 200;
    /** 单页条数硬上限（即使模型传入更大 limit 也会被截断到此值）。 */
    private static final int MAX_LIMIT = 500;
    /** 单次输出的字符硬上限，含 "[DIR] "/"[FILE] " 前缀与结尾游标提示。 */
    private static final int MAX_OUTPUT_CHARS = 60_000;
    private static final int SMALL_SUBTREE_ENTRIES = 12;

    /** 上游终端 Talent，用于反射取守卫对象，以及回落委托不可访问的逻辑路径列举。 */
    final TerminalTalent terminal;
    /** Solon 路径安全守卫（resolveSafePath/isIgnored/isReadDenied）。 */
    final TerminalSupport support;
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
     * 强调 200 项默认页与 {@code after} 游标续页机制，避免模型误以为一次可拿全量结果。
     *
     * @param prompt 当前提示词上下文（未使用）
     * @return 中文使用指令
     */
    @Override
    public String getInstruction(Prompt prompt) {
        return "ls 默认显示浅层树：小子树展开到第三级，大目录收起；recursive=true 或 max_depth 显示递归路径树并分页。已知文件名模式用 glob。";
    }

    /**
     * 工具入口：列出目录条目（字典序分页）。
     *
     * <p>处理流程：
     * <ol>
     *   <li>逻辑路径（{@code @xxx}）：若挂载来源不可本地访问，回落委托 {@link TerminalTalent#ls} 处理；</li>
     *   <li>参数归一：{@code after} 游标长度校验（&le; 4096 字符）、{@code limit} 钳制到 [1, {@value #MAX_LIMIT}]；</li>
     *   <li>路径校验：经 {@code resolveSafePath} 取得安全路径，不存在或非目录返回错误字符串；</li>
     *   <li>遍历收集：{@link Files#walkFileTree} 按递归选项或显式深度上限遍历，
     *       应用忽略规则、沙箱读策略与隐藏文件过滤后，用容量为 {@code pageSize+1} 的
     *       最大堆维持"字典序最小的 pageSize+1 条"；</li>
     *   <li>出页渲染：排序、截断到最后一条，拼接输出并在末尾附游标提示。</li>
     * </ol>
     *
     * <p>所有错误（路径不存在、非目录、游标过长）均返回字符串而非抛异常，
     * 以便模型读到错误文本后自行修正参数重试。
     *
     * @param path         目录相对路径或逻辑路径；null/空表示当前工作区（{@code .}）
     * @param recursive    是否递归全部层级；不传时可由 max_depth 启用递归
     * @param showHidden   是否包含以 {@code .} 开头的条目；null 视为 false
     * @param after        上一页返回的 {@code after} 游标，仅列出字典序严格大于它的条目；
     *                     超过 4096 字符直接拒绝
     * @param limit        单页条数，默认 {@value #DEFAULT_LIMIT}，钳制上限 {@value #MAX_LIMIT}
     * @param mode         auto（默认）或 flat；递归时显示路径树
     * @param maxDepth     相对目标的条目深度，范围 1～64；单独设置即启用递归
     * @param __cwd        当前目录（Solon 注入）；null 时使用工作区根
     * @return 格式化列表（{@code [DIR]}/{@code [FILE]} 前缀）+ 页尾状态与续页游标；或错误字符串
     * @throws IOException 遍历文件树时发生 I/O 错误（调用方捕获转为会话错误）
     */
    @ToolMapping(name = "ls", description = "查看目录树。默认展开小子树到第三级，大目录收起；设置 max_depth 即按指定深度递归，recursive=true 可不限深度递归；支持 after 续页。")
    public String ls(
            @Param(value = "path", required = false, description = "目录相对路径或逻辑路径，默认当前工作区") String path,
            @Param(value = "recursive", required = false, description = "是否递归全部子目录；仍受分页和输出上限约束") Boolean recursive,
            @Param(value = "show_hidden", required = false, description = "是否显示隐藏文件") Boolean showHidden,
            @Param(value = "after", required = false, description = "上一页返回的 after 游标") String after,
            @Param(value = "limit", required = false, description = "当前层最多返回条数，默认 200，最大 500；小子树展开另受有界限制") Integer limit,
            @Param(value = "mode", required = false, description = "auto（默认，小子树展开到第三级）或 flat（只列当前层）；recursive=true 时显示递归路径树") String mode,
            @Param(value = "max_depth", required = false, description = "相对目标的递归深度，1～64；单独设置即启用递归；与 recursive=false 或 mode=flat 冲突") Integer maxDepth,
            String __cwd) throws IOException {
        String selectedMode = mode == null || mode.isEmpty() ? "auto" : mode;
        if (!"auto".equals(selectedMode) && !"flat".equals(selectedMode)) {
            return "错误：mode 只能是 auto 或 flat。";
        }
        if (maxDepth != null && (maxDepth < 1 || maxDepth > 64)) {
            return "错误：max_depth 取值范围为 1～64。";
        }
        if (maxDepth != null && Boolean.FALSE.equals(recursive)) {
            return "错误：max_depth 会启用递归，不能同时设置 recursive=false。";
        }
        boolean effectiveRecursive = Boolean.TRUE.equals(recursive) || maxDepth != null;
        if (effectiveRecursive && "flat".equals(selectedMode)) {
            return "错误：递归列举（recursive=true 或 max_depth）与 mode=flat 不能同时使用。";
        }
        String selectedPath = path == null || path.isEmpty() ? "." : path;
        if (selectedPath.startsWith("@")) {
            ResolvedResource resource = mounts.resolveResource(selectedPath);
            if (!resource.getSource().capabilities().isLocalPathAccessible()
                    || !resource.getLocalPath().isPresent()) {
                String original = terminal.ls(selectedPath, effectiveRecursive, showHidden, __cwd);
                return effectiveRecursive
                        ? "提示：非本地逻辑挂载沿用 Solon 原始 ls；不保证完整递归，也不应用 max_depth/after/limit。\n" + original
                        : "auto".equals(selectedMode)
                        ? "提示：非本地逻辑挂载暂不支持自适应概览，沿用 Solon 原始 ls。\n" + original
                        : original;
            }
        }
        if (after != null && after.length() > 4096) return "错误：after 游标过长。";
        if ((effectiveRecursive || "flat".equals(selectedMode))
                && after != null && after.matches("[01]:.+")) {
            return "错误：auto 模式的 after 游标不能用于 flat/recursive；请不带 after 重新列出。";
        }
        int pageSize = limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        Path workPath = __cwd == null ? workspace : Paths.get(__cwd).toAbsolutePath().normalize();
        Path target = support.resolveSafePath(workPath, selectedPath, false,
                terminal.isSandboxEnabled(), false, null);
        com.udata.harness.repository.ModelConfigurationFiles.requireAllowed(target);
        if (!Files.exists(target)) return "错误：路径不存在";
        if (!Files.isDirectory(target)) return "错误：目标不是目录";
        Path policyRoot = support.getSandboxPolicyRoot(workPath, selectedPath);
        if (!effectiveRecursive && "auto".equals(selectedMode)) {
            return adaptiveLs(target, workPath, policyRoot, selectedPath,
                    Boolean.TRUE.equals(showHidden), after, pageSize);
        }
        PriorityQueue<Entry> page = new PriorityQueue<>(Comparator.comparing((Entry entry) -> entry.key).reversed());
        boolean includeHidden = Boolean.TRUE.equals(showHidden);
        int depth = effectiveRecursive ? maxDepth == null ? Integer.MAX_VALUE : maxDepth : 1;
        int[] depthBoundaries = new int[1];
        int[] failures = new int[1];
        boolean[] cancelled = new boolean[1];
        Files.walkFileTree(target, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), depth,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                        if (Thread.currentThread().isInterrupted()) { cancelled[0] = true; return FileVisitResult.TERMINATE; }
                        if (directory.equals(target)) return FileVisitResult.CONTINUE;
                        if (filtered(directory)) return FileVisitResult.SKIP_SUBTREE;
                        offer(directory, true);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        if (Thread.currentThread().isInterrupted()) { cancelled[0] = true; return FileVisitResult.TERMINATE; }
                        if (!filtered(file)) {
                            boolean boundary = effectiveRecursive && attrs.isDirectory();
                            if (boundary) depthBoundaries[0]++;
                            offer(file, attrs.isDirectory(), boundary);
                        }
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException error) {
                        failures[0]++;
                        return FileVisitResult.CONTINUE;
                    }

                    private boolean filtered(Path item) {
                        return com.udata.harness.repository.ModelConfigurationFiles.isProtected(item) || support.isIgnored(workPath, item)
                                || support.isReadDenied(policyRoot, item, terminal.isSandboxEnabled(), null)
                                || (!includeHidden && item.getFileName().toString().startsWith("."));
                    }

                    private void offer(Path item, boolean directory) {
                        offer(item, directory, false);
                    }

                    private void offer(Path item, boolean directory, boolean boundary) {
                        String key = target.relativize(item).toString().replace('\\', '/');
                        if (after != null && key.compareTo(after) <= 0) return;
                        page.offer(new Entry(key, directory, boundary));
                        if (page.size() > pageSize + 1) page.poll();
                    }
                });
        if (cancelled[0]) return "目录扫描已取消，结果不完整；请缩小 path 后重试。";
        if (page.isEmpty()) return (after == null ? "(目录为空)" : "(没有更多目录项)")
                + listingWarnings(depthBoundaries[0], failures[0], maxDepth);
        List<Entry> ordered = new ArrayList<>(page);
        ordered.sort(Comparator.comparing(entry -> entry.key));
        boolean hasMore = ordered.size() > pageSize;
        if (hasMore) ordered.remove(ordered.size() - 1);
        String root = ".".equals(selectedPath) ? "" : selectedPath.replace('\\', '/').replaceAll("/+$", "") + "/";
        StringBuilder output;
        String lastKey;
        if (effectiveRecursive) {
            String heading = (".".equals(selectedPath) ? "." : selectedPath.replace('\\', '/'))
                    + "/ （递归路径树；" + (maxDepth == null ? "不限深度" : "max_depth=" + maxDepth)
                    + "；续页可能重复显示上级目录）\n";
            List<Entry> visible = new ArrayList<>(ordered);
            String tree = recursiveTree(visible);
            while (!visible.isEmpty() && heading.length() + tree.length() > MAX_OUTPUT_CHARS - 512) {
                visible.remove(visible.size() - 1);
                hasMore = true;
                tree = recursiveTree(visible);
            }
            if (visible.isEmpty()) return "错误：目录项路径过长，无法在输出上限内显示。";
            output = new StringBuilder(heading).append(tree);
            lastKey = visible.get(visible.size() - 1).key;
        } else {
            output = new StringBuilder();
            lastKey = null;
            for (Entry entry : ordered) {
                String line = (entry.directory ? "[DIR] " : "[FILE] ")
                        + root + entry.key + (entry.directory ? "/" : "") + "\n";
                if (output.length() + line.length() + entry.key.length() > MAX_OUTPUT_CHARS - 512) {
                    hasMore = true;
                    break;
                }
                output.append(line);
                lastKey = entry.key;
            }
        }
        if (lastKey == null) return "错误：目录项路径过长，无法在输出上限内显示。";
        if (hasMore) {
            output.append("--- [内容未完] ---\n继续列出请使用 after=")
                    .append(lastKey)
                    .append(", limit=").append(pageSize);
            if (effectiveRecursive && maxDepth == null) output.append(", recursive=true");
            if (maxDepth != null) output.append(", max_depth=").append(maxDepth);
        } else {
            output.append(depthBoundaries[0] > 0 || failures[0] > 0
                    ? "--- [已列出可访问范围；递归结果不保证完整] ---" : "--- [目录列举完毕] ---");
        }
        output.append(listingWarnings(depthBoundaries[0], failures[0], maxDepth));
        return output.toString();
    }

    /** Direct Java callers retain the original signature; the model-facing tool includes mode. */
    public String ls(String path, Boolean recursive, Boolean showHidden, String after,
                     Integer limit, String __cwd) throws IOException {
        return ls(path, recursive, showHidden, after, limit, null, null, __cwd);
    }

    public String ls(String path, Boolean recursive, Boolean showHidden, String after,
                     Integer limit, String mode, String __cwd) throws IOException {
        return ls(path, recursive, showHidden, after, limit, mode, null, __cwd);
    }

    private static String listingWarnings(int boundaries, int failures, Integer depth) {
        StringBuilder warning = new StringBuilder();
        if (boundaries > 0) warning.append("\n深度限制 max_depth=").append(depth).append("：有 ").append(boundaries)
                .append(" 个边界目录未展开（可能含更深内容）。after 仅续接本深度范围内的目录项；")
                .append("查看更深内容请对标记目录调用 ls，或移除/增大 max_depth。\n");
        if (failures > 0) warning.append("\n警告：有 ").append(failures).append(" 个路径读取失败，结果不完整。\n");
        return warning.toString();
    }

    private String adaptiveLs(Path target, Path workPath, Path policyRoot, String selectedPath,
                              boolean showHidden, String after, int pageSize) throws IOException {
        if (after != null && !after.matches("[01]:.+")) {
            return "错误：auto 模式的 after 游标须来自本模式输出；请不带 after 重新列出，或使用 mode=flat 续接旧游标。";
        }
        String cursor = after;
        PriorityQueue<Entry> page = new PriorityQueue<>(
                Comparator.comparing((Entry entry) -> autoKey(entry)).reversed());
        int[] failures = new int[1];
        Files.walkFileTree(target, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), 1,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) {
                        if (!directory.equals(target)) offer(directory, true);
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                        offer(file, attrs.isDirectory());
                        return FileVisitResult.CONTINUE;
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path file, IOException error) {
                        failures[0]++;
                        return FileVisitResult.CONTINUE;
                    }

                    private void offer(Path item, boolean directory) {
                        if (filtered(workPath, policyRoot, item, showHidden)) return;
                        Entry entry = new Entry(relative(target, item), directory);
                        if (cursor != null && autoKey(entry).compareTo(cursor) <= 0) return;
                        page.offer(entry);
                        if (page.size() > pageSize + 1) page.poll();
                    }
                });
        if (page.isEmpty()) return after == null ? "(目录为空)" : "(没有更多目录项)";
        List<Entry> ordered = new ArrayList<>(page);
        ordered.sort(Comparator.comparing(BoundedLsTool::autoKey));
        boolean hasMore = ordered.size() > pageSize;
        if (hasMore) ordered.remove(ordered.size() - 1);
        String root = ".".equals(selectedPath) ? "." : selectedPath.replace('\\', '/').replaceAll("/+$", "");
        String heading = root + "/ （目录优先；小目录自动展开）\n";
        List<RootView> views = new ArrayList<>();
        int baseline = heading.length();
        for (Entry entry : ordered) {
            DirectoryOverview overview = null;
            if (entry.directory) {
                try {
                    overview = inspectSubtree(target.resolve(entry.key), workPath, policyRoot, showHidden);
                } catch (IOException error) {
                    overview = new DirectoryOverview();
                    overview.failed = true;
                }
            }
            RootView view = new RootView(entry, overview);
            int length = rootLine(view, false, false).length();
            if (baseline + length > MAX_OUTPUT_CHARS - 512) {
                hasMore = true;
                break;
            }
            baseline += length;
            views.add(view);
        }
        if (views.isEmpty()) return "错误：目录项路径过长，无法在输出上限内显示。";
        int remaining = MAX_OUTPUT_CHARS - 512 - baseline;
        for (RootView view : views) {
            if (view.overview == null || view.overview.large || view.overview.entries.isEmpty()) continue;
            int extra = rootLine(view, false, true).length()
                    + childTree(view.overview, false).length()
                    - rootLine(view, false, false).length();
            if (extra <= remaining) {
                view.expanded = true;
                remaining -= extra;
            }
        }
        StringBuilder output = new StringBuilder(heading);
        for (int i = 0; i < views.size(); i++) {
            RootView view = views.get(i);
            boolean last = i == views.size() - 1 && !hasMore;
            output.append(rootLine(view, last, view.expanded));
            if (view.expanded) output.append(childTree(view.overview, last));
        }
        String lastKey = autoKey(views.get(views.size() - 1).entry);
        if (hasMore) output.append("--- [内容未完] ---\n继续列出请使用 after=")
                .append(lastKey).append(", limit=").append(pageSize);
        else output.append("--- [目录列举完毕] ---");
        if (failures[0] > 0) output.append("\n警告：有 ").append(failures[0])
                .append(" 个当前层路径读取失败，结果可能不完整。");
        return output.toString();
    }

    private static String rootLine(RootView view, boolean last, boolean expanded) {
        StringBuilder line = new StringBuilder(last ? "└── " : "├── ")
                .append(view.entry.key).append(view.entry.directory ? "/" : "");
        DirectoryOverview overview = view.overview;
        if (overview != null) {
            if (overview.large) {
                line.append(" [已收起：浅层至少 ").append(SMALL_SUBTREE_ENTRIES + 1).append(" 项；样例：");
                for (int i = 0; i < Math.min(3, overview.entries.size()); i++) {
                    if (i > 0) line.append("、");
                    Entry sample = overview.entries.get(i);
                    line.append(sample.key).append(sample.directory ? "/" : "");
                }
                line.append("]");
            } else if (overview.entries.isEmpty()) {
                line.append(overview.failed ? " [读取不完整]" : " [空目录]");
            } else {
                line.append(" [浅层 ").append(overview.entries.size()).append(" 项");
                if (overview.hasDeeper) line.append("；更深层未扫描");
                if (overview.failed) line.append("；读取不完整");
                if (!expanded) line.append("；展开因输出预算省略");
                line.append("]");
            }
        }
        return line.append('\n').toString();
    }

    private static String childTree(DirectoryOverview overview, boolean parentLast) {
        List<TreeNode> roots = new ArrayList<>();
        for (Entry entry : overview.entries) {
            String[] components = entry.key.split("/");
            List<TreeNode> level = roots;
            for (int index = 0; index < components.length; index++) {
                String name = components[index];
                TreeNode node = null;
                for (TreeNode candidate : level) {
                    if (candidate.name.equals(name)) { node = candidate; break; }
                }
                if (node == null) {
                    node = new TreeNode(name, index < components.length - 1 || entry.directory);
                    level.add(node);
                }
                level = node.children;
            }
        }
        StringBuilder output = new StringBuilder();
        appendTree(output, roots, parentLast ? "    " : "│   ");
        return output.toString();
    }

    private static String recursiveTree(List<Entry> entries) {
        List<TreeNode> roots = new ArrayList<>();
        for (Entry entry : entries) {
            String[] components = entry.key.split("/");
            List<TreeNode> level = roots;
            for (int index = 0; index < components.length; index++) {
                String name = components[index];
                TreeNode node = null;
                for (TreeNode candidate : level) {
                    if (candidate.name.equals(name)) { node = candidate; break; }
                }
                if (node == null) {
                    node = new TreeNode(name, index < components.length - 1 || entry.directory);
                    level.add(node);
                }
                if (index == components.length - 1) node.depthBoundary = entry.depthBoundary;
                level = node.children;
            }
        }
        StringBuilder output = new StringBuilder();
        appendTree(output, roots, "");
        return output.toString();
    }

    private static void appendTree(StringBuilder output, List<TreeNode> nodes, String prefix) {
        nodes.sort(Comparator.comparing((TreeNode node) -> !node.directory).thenComparing(node -> node.name));
        for (int i = 0; i < nodes.size(); i++) {
            TreeNode node = nodes.get(i);
            boolean last = i == nodes.size() - 1;
            output.append(prefix).append(last ? "└── " : "├── ")
                    .append(node.name).append(node.directory ? "/" : "")
                    .append(node.depthBoundary ? " [达到 max_depth，未展开]" : "").append('\n');
            appendTree(output, node.children, prefix + (last ? "    " : "│   "));
        }
    }

    private DirectoryOverview inspectSubtree(Path directory, Path workPath, Path policyRoot,
                                             boolean showHidden) throws IOException {
        DirectoryOverview overview = new DirectoryOverview();
        Files.walkFileTree(directory, java.util.EnumSet.noneOf(java.nio.file.FileVisitOption.class), 2,
                new SimpleFileVisitor<Path>() {
                    @Override
                    public FileVisitResult preVisitDirectory(Path item, BasicFileAttributes attrs) {
                        if (item.equals(directory)) return FileVisitResult.CONTINUE;
                        if (filtered(workPath, policyRoot, item, showHidden)) return FileVisitResult.SKIP_SUBTREE;
                        return remember(item, true);
                    }

                    @Override
                    public FileVisitResult visitFile(Path item, BasicFileAttributes attrs) {
                        if (filtered(workPath, policyRoot, item, showHidden)) return FileVisitResult.CONTINUE;
                        if (attrs.isDirectory() && directory.relativize(item).getNameCount() >= 2) {
                            overview.hasDeeper = true;
                        }
                        return remember(item, attrs.isDirectory());
                    }

                    @Override
                    public FileVisitResult visitFileFailed(Path item, IOException error) {
                        overview.failed = true;
                        return FileVisitResult.CONTINUE;
                    }

                    private FileVisitResult remember(Path item, boolean isDirectory) {
                        overview.entries.add(new Entry(relative(directory, item), isDirectory));
                        if (overview.entries.size() > SMALL_SUBTREE_ENTRIES) {
                            overview.large = true;
                            return FileVisitResult.TERMINATE;
                        }
                        return FileVisitResult.CONTINUE;
                    }
                });
        overview.entries.sort(Comparator.comparing(entry -> entry.key));
        return overview;
    }

    boolean filtered(Path workPath, Path policyRoot, Path item, boolean showHidden) {
        return com.udata.harness.repository.ModelConfigurationFiles.isProtected(item) || support.isIgnored(workPath, item)
                || support.isReadDenied(policyRoot, item, terminal.isSandboxEnabled(), null)
                || (!showHidden && item.getFileName().toString().startsWith("."));
    }

    static String relative(Path root, Path item) {
        return root.relativize(item).toString().replace('\\', '/');
    }

    static String autoKey(Entry entry) {
        return (entry.directory ? "0:" : "1:") + entry.key;
    }

    private static final class DirectoryOverview {
        final List<Entry> entries = new ArrayList<>();
        boolean large;
        boolean failed;
        boolean hasDeeper;
    }

    private static final class RootView {
        final Entry entry;
        final DirectoryOverview overview;
        boolean expanded;

        RootView(Entry entry, DirectoryOverview overview) {
            this.entry = entry;
            this.overview = overview;
        }
    }

    private static final class TreeNode {
        final String name;
        final boolean directory;
        final List<TreeNode> children = new ArrayList<>();
        boolean depthBoundary;

        TreeNode(String name, boolean directory) {
            this.name = name;
            this.directory = directory;
        }
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
        final boolean depthBoundary;

        /**
         * 构造条目。
         *
         * @param key       字典序排序键（相对路径）
         * @param directory 是否为目录
         */
        Entry(String key, boolean directory) {
            this(key, directory, false);
        }

        Entry(String key, boolean directory, boolean depthBoundary) {
            this.key = key;
            this.directory = directory;
            this.depthBoundary = depthBoundary;
        }
    }
}
