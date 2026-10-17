package org.noear.solon.ai.talents.cli;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.annotation.Param;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 受限分页的文件名搜索工具（glob）。
 *
 * <p>核心特性：
 * <ul>
 *   <li><b>稳定排序</b>：结果按键（路径）字典序排序，保证同一查询多次执行顺序一致；</li>
 *   <li><b>有界输出</b>：单页默认返回 100 项、上限 500 项，正文另受 {@link #MAX_BODY_CHARS} 字符预算约束，
 *       避免大目录树撑爆上下文；</li>
 *   <li><b>游标续页</b>：内容未完时输出 {@code after} 游标（上一页最后一条的键），
 *       调用方带相同 {@code path}/{@code pattern} 与 {@code after} 即可继续取下一页；</li>
 *   <li><b>安全策略</b>：遍历时跳过隐藏目录与沙盒策略拒绝的路径，读取失败的路径计数并在结果中告警；</li>
 *   <li><b>非本地挂载降级</b>：非本地逻辑挂载不支持分页，沿用 Solon 原始 glob 并提示结果可能不完整。</li>
 * </ul>
 *
 * <p>匹配语义：分别尝试匹配相对搜索根的路径、绝对路径以及文件名本身，
 * 兼容 {@code **&#47;*.java} 这类通配与直接指定文件名的写法；pattern 中的 {@code \} 会统一为 {@code /}。
 *
 * @see #glob(String, String, String, Integer, String)
 */
public final class BoundedGlobTool extends AbsTalent {
    private static final int DEFAULT_LIMIT = 200;
    private static final int MAX_LIMIT = 500;
    private static final int MAX_BODY_CHARS = 60_000;
    // Java 8 anonymous visitors must access this directly: a synthetic access$0
    // method can disappear when a development JVM sees mixed hot-reloaded classes.
    final SearchToolAccess access;

    /**
     * 创建 glob 工具实例。
     *
     * @param terminal  终端工具（提供非本地挂载的降级 glob 能力）
     * @param workspace 工作区根目录，用于解析相对路径与沙盒策略
     */
    public BoundedGlobTool(TerminalTalent terminal, String workspace) {
        access = new SearchToolAccess(terminal, workspace);
    }

    /**
     * 返回注入到智能体的工具使用说明（稳定排序、after 续页与 grep 分工提示）。
     *
     * @param prompt 当前提示词上下文
     * @return 工具使用说明文本
     */
    @Override
    public String getInstruction(Prompt prompt) {
        return "用 glob 按文件名定位路径；搜索文件内容用 grep。结果未完时按 after 续页。";
    }

    /**
     * 按 glob 模式分页查找文件名。
     *
     * <p>执行流程：非本地逻辑挂载直接降级为原始 glob；否则遍历目标目录树（跳过隐藏/被拒目录），
     * 逐项匹配后维护一个容量为 {@code pageSize + 1} 的按键逆序最大堆，遍历结束后按键升序
     * 截取当前页，并在超出 {@value #MAX_BODY_CHARS} 字符预算时提前截断。
     *
     * @param pattern glob 模式（{@code \} 会归一化为 {@code /}），必填
     * @param path    搜索根目录，缺省为当前工作区 {@code .}
     * @param after   续页游标（上一页最后一条的键），为空表示取首页
     * @param limit   单页最多返回项数，缺省 {@value #DEFAULT_LIMIT}，上限 {@value #MAX_LIMIT}
     * @param __cwd   当前工作目录（由框架注入）
     * @return 分页结果文本；内容未完时附带 {@code after}/{@code limit} 续页提示，参数非法时返回错误描述
     * @throws IOException 目录遍历期间发生 I/O 异常
     */
    @ToolMapping(name = "glob", description = "按文件名模式查找路径；搜索代码正文请用 grep。结果按路径排序并支持续页。")
    public String glob(
            @Param(value = "pattern", description = "glob 模式，例如 **/*.java") String pattern,
            @Param(value = "path", required = false, description = "搜索根目录，默认当前工作区") String path,
            @Param(value = "after", required = false, description = "相同 path/pattern 的上一页输出的 after 游标") String after,
            @Param(value = "limit", required = false, description = "单页最多返回项数，默认 200，最大 500") Integer limit,
            String __cwd) throws IOException {
        if (pattern == null || pattern.isEmpty()) return "错误：pattern 不能为空。";
        String selectedPath = path == null || path.isEmpty() ? "." : path;
        if (access.isNonLocalVirtual(selectedPath)) {
            if (after != null || limit != null) {
                return "错误：非本地逻辑挂载暂不支持 glob 的 after/limit 分页；请缩小 pattern 或 path。";
            }
            return "提示：非本地逻辑挂载沿用 Solon 原始 glob，结果上限与续页行为不同，可能不完整。\n"
                    + access.terminal.glob(pattern, selectedPath, __cwd);
        }
        if (after != null && after.length() > 4096) return "错误：after 游标过长。";
        int pageSize = limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        Path workPath = access.workPath(__cwd);
        Path target = access.resolve(workPath, selectedPath);
        if (!Files.exists(target)) return "错误：路径不存在。";
        final PathMatcher matcher;
        try {
            matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern.replace('\\', '/'));
        } catch (IllegalArgumentException e) {
            return "错误：无效的 glob 模式，请检查 pattern。";
        }
        Path policyRoot = access.support.getSandboxPolicyRoot(workPath, selectedPath);
        PriorityQueue<Entry> page = new PriorityQueue<>(Comparator.comparing((Entry entry) -> entry.key).reversed());
        int[] skipped = new int[1];
        Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return access.hiddenOrDenied(workPath, policyRoot, dir)
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (attrs.isDirectory() || access.hiddenOrDenied(workPath, policyRoot, file)) {
                    return FileVisitResult.CONTINUE;
                }
                Path relative = target.relativize(file);
                if (matcher.matches(relative) || matcher.matches(file)
                        || (relative.toString().isEmpty() && matcher.matches(file.getFileName()))) {
                    String key = SearchToolAccess.relative(target, file);
                    if (after == null || key.compareTo(after) > 0) {
                        page.offer(new Entry(key, access.display(workPath, selectedPath, target, file)));
                        if (page.size() > pageSize + 1) page.poll();
                    }
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) {
                skipped[0]++;
                return FileVisitResult.CONTINUE;
            }
        });
        if (page.isEmpty()) {
            return (after == null ? "未找到匹配文件。" : "(没有更多匹配文件)")
                    + (skipped[0] == 0 ? "" : "\n警告：有 " + skipped[0] + " 个路径读取失败，结果可能不完整。");
        }
        List<Entry> ordered = new ArrayList<>(page);
        ordered.sort(Comparator.comparing(entry -> entry.key));
        boolean hasMore = ordered.size() > pageSize;
        if (hasMore) ordered.remove(ordered.size() - 1);
        StringBuilder output = new StringBuilder();
        String lastKey = null;
        for (Entry entry : ordered) {
            String line = "[FILE] " + entry.display + "\n";
            if (output.length() + line.length() > MAX_BODY_CHARS) {
                hasMore = true;
                break;
            }
            output.append(line);
            lastKey = entry.key;
        }
        if (lastKey == null) return "错误：单个文件路径过长，无法在输出预算内展示。";
        if (hasMore) {
            output.append("--- [内容未完] ---\n继续搜索请使用 after=")
                    .append(lastKey).append(", limit=").append(pageSize);
        } else {
            output.append("--- [搜索完毕] ---");
        }
        if (skipped[0] > 0) {
            output.append("\n警告：有 ").append(skipped[0]).append(" 个路径读取失败，结果可能不完整。");
        }
        return output.toString();
    }

    /**
     * 单条匹配结果：{@code key} 为排序与续页所用的相对路径键，{@code display} 为展示用路径。
     */
    private static final class Entry {
        final String key;
        final String display;
        Entry(String key, String display) { this.key = key; this.display = display; }
    }
}
