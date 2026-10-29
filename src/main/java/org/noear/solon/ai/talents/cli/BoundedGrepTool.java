package org.noear.solon.ai.talents.cli;

import com.udata.harness.common.context.ContextArtifactStore;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.talent.AbsTalent;
import org.noear.solon.annotation.Param;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.PushbackReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Comparator;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 受限的文件内容搜索工具（grep）：多输出模式、稳定分页、显式披露省略项。
 *
 * <p>核心特性：
 * <ul>
 *   <li><b>四种输出模式</b>：{@code summary}（默认，按文件聚合并显示少量定位行）、
 *       {@code content}（逐行展示匹配行及前后文）、
 *       {@code files_with_matches}（只定位文件）、{@code count}（统计每个文件的匹配行数）；</li>
 *   <li><b>有界输出</b>：单页默认 {@value #DEFAULT_LIMIT} 行、上限 {@value #MAX_LIMIT} 行，
 *       正文另受 {@value #MAX_BODY_CHARS} 字符预算约束，单条片段截断至 {@value #MAX_SNIPPET_CHARS} 字符；</li>
 *   <li><b>游标续页</b>：结果按键（content 模式为 {@code 文件路径#行号}，其余模式为文件路径）
 *       字典序排序，内容未完时输出 {@code after} 游标，携带相同查询参数即可继续取下一页；</li>
 *   <li><b>安全策略</b>：遍历时跳过隐藏目录与沙盒策略拒绝的路径，以及非文本文件；</li>
 *   <li><b>显式省略</b>：超过 {@value #MAX_FILE_BYTES} 字节的文件、超过 {@value #MAX_LINE_CHARS}
 *       字符的超长行与读取失败的文件不参与匹配，计数后在结果尾部统一告警“搜索不完整”；</li>
 *   <li><b>非本地挂载降级</b>：非本地逻辑挂载沿用 Solon 原始 grep，且不支持输出模式、
 *       前后文与分页参数；</li>
 *   <li><b>归档检索</b>：{@code path=@tool-results} 时检索当前会话已归档的历史工具结果
 *       （仅接受 {@code [a-f0-9]{64}.txt} 形式的归档文件）。</li>
 * </ul>
 *
 * @see #grep(String, String, String, String, Integer, String, Integer, Boolean, String, String)
 */
public final class BoundedGrepTool extends AbsTalent {
    /** 未显式传 limit 时的单页默认结果数。 */
    private static final int DEFAULT_LIMIT = 200;
    /** 单页结果数硬上限（更大的 limit 会被截断到该值）。 */
    private static final int MAX_LIMIT = 500;
    /** 单次输出正文的字符预算；累计超出即触发续页提示。 */
    private static final int MAX_BODY_CHARS = 32_000;
    private static final int SUMMARY_FILES = 20;
    private static final int SUMMARY_SAMPLES = 2;
    private static final int SUMMARY_SNIPPET_CHARS = 180;
    /** 读入单行的字符上限；超出部分丢弃并将该行标记为超长（不参与匹配）。 */
    private static final int MAX_LINE_CHARS = 16 * 1024;
    /** 单条匹配片段的展示上限（字符），超出截断并标记。 */
    private static final int MAX_SNIPPET_CHARS = 1000;
    /** 参与搜索的单文件大小上限（字节），超过则跳过并计数告警。 */
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    /** 路径解析与安全策略访问器（隐藏目录、沙盒拒绝、文本识别等）。 */
    final SearchToolAccess access;
    /** {@code @tool-results} 会话归档的虚拟路径解析器。 */
    final ToolResultPaths toolResults;

    /**
     * 创建 grep 工具实例（不接入会话归档，{@code @tool-results} 虚拟路径不可用）。
     *
     * @param terminal  终端工具（提供非本地挂载的降级 grep 能力）
     * @param workspace 工作区根目录，用于解析相对路径与沙盒策略
     */
    public BoundedGrepTool(TerminalTalent terminal, String workspace) {
        this(terminal, workspace, null);
    }

    /**
     * 创建 grep 工具实例。
     *
     * @param terminal  终端工具（提供非本地挂载的降级 grep 能力）
     * @param workspace 工作区根目录，用于解析相对路径与沙盒策略
     * @param store     上下文产物存档，用于解析 {@code @tool-results} 虚拟路径；
     *                  为 {@code null} 时检索归档会返回错误文案（fail-closed）
     */
    public BoundedGrepTool(TerminalTalent terminal, String workspace, ContextArtifactStore store) {
        access = new SearchToolAccess(terminal, workspace);
        toolResults = new ToolResultPaths(store);
    }

    /**
     * 返回注入到智能体的工具使用说明（输出模式分工与 {@code @tool-results} 归档检索提示）。
     *
     * @param prompt 当前提示词上下文
     * @return 工具使用说明文本
     */
    @Override
    public String getInstruction(Prompt prompt) {
        return "按业务含义搜索时，先推导源码或配置中的实现表示（如表达式、数值与单位、常量、API 和配置项），结合项目约定构造正则或分次检索，不要只翻译业务词。"
                + "先用 grep 的默认概览定位文件和行号，再用 read 确认实际行为；需要逐条匹配和续页时使用 output_mode=content。"
                + "未命中不能直接证明实现不存在，应调整表达式或范围继续查证。path=@tool-results 仅搜索当前会话归档。";
    }

    /**
     * 按正则搜索文件内容，支持概览、逐行、文件名和计数输出。
     *
     * <p>执行流程：目标为非本地逻辑挂载时，若请求了输出模式、前后文或分页等高级参数
     * 直接返回错误，否则降级为 Solon 原始 grep（结果可能不完整）。本地搜索则先校验
     * pattern、output_mode、include，再遍历目标目录树（跳过隐藏/被拒目录、非文本文件、
     * 超大文件与超长行），{@code content} 模式下维护一个容量为 {@code pageSize + 1} 的
     * 按键逆序最大堆，遍历结束后按键升序截取当前页，并在超出 {@value #MAX_BODY_CHARS}
     * 字符预算时提前截断；所有被跳过的项目计数并在尾部告警。</p>
     *
     * @param pattern      正则表达式；{@code literal=true} 时按字面匹配。必填，不超过 1000 字符
     * @param path         搜索根目录，缺省为当前工作区 {@code .}；{@code @tool-results} 表示会话归档
     * @param include      文件名 glob 过滤（如 {@code *.{ts,tsx}}），仅匹配文件名本身，可空
     * @param outputMode   {@code content}（默认）/ {@code files_with_matches} / {@code count}
     * @param contextLines content 模式下每条匹配前后各展示的行数，缺省 0、上限 3
     * @param after        续页游标（上一页最后一条的键），为空表示取首页
     * @param limit        单页最多返回项数，缺省 {@value #DEFAULT_LIMIT}、上限 {@value #MAX_LIMIT}
     * @param literal      {@code true} 时按字面字符串搜索，不将 pattern 当正则
     * @param __cwd        当前工作目录（由框架注入）
     * @param __sessionId  当前会话 ID（由框架注入；解析 {@code @tool-results} 时必需）
     * @return 结果页文本，尾部带“内容未完/搜索完毕”标记；参数非法时返回 {@code 错误：…} 说明
     * @throws IOException 遍历目标路径失败时抛出
     */
    @ToolMapping(name = "grep", description = "定位文本所在文件和行号。默认返回命中分布与关键片段；需要逐条结果时用 output_mode=content，再用 read 查看代码。")
    public String grep(
            @Param(value = "pattern", description = "正则表达式；若需字面搜索请设置 literal=true") String pattern,
            @Param(value = "path", required = false, description = "搜索根目录，默认工作区") String path,
            @Param(value = "include", required = false, description = "文件名 glob 模式，例如 *.{ts,tsx}") String include,
            @Param(value = "output_mode", required = false, description = "summary（默认）、content、files_with_matches 或 count") String outputMode,
            @Param(value = "context", required = false, description = "content 模式下每个匹配前后各展示几行，最多 3 行") Integer contextLines,
            @Param(value = "after", required = false, description = "相同 pattern/path/include/output_mode 的上一页 after 游标") String after,
            @Param(value = "limit", required = false, description = "单页结果数，默认 200，最大 500") Integer limit,
            @Param(value = "literal", required = false, description = "true 时按字面字符串搜索，不将 pattern 当正则") Boolean literal,
            String __cwd, String __sessionId) throws IOException {
        if (pattern == null || pattern.isEmpty()) return "错误：pattern 不能为空。";
        if (pattern.length() > 1000) return "错误：pattern 过长（最多 1000 字符）。";
        String selectedPath = path == null || path.isEmpty() ? "." : path;
        boolean virtualResults = ToolResultPaths.isVirtual(selectedPath);
        if (!virtualResults && access.isNonLocalVirtual(selectedPath)) {
            if (after != null || limit != null || contextLines != null || Boolean.TRUE.equals(literal)
                    || (outputMode != null && !outputMode.isEmpty() && !"content".equals(outputMode))) {
                return "错误：非本地逻辑挂载暂不支持 grep 的输出模式、前后文及分页参数；请缩小 path/include 后重试。";
            }
            return "提示：非本地逻辑挂载沿用 Solon 原始 grep，结果上限与续页行为不同，可能不完整。\n"
                    + access.terminal.grep(pattern, selectedPath, include, __cwd);
        }
        String mode = outputMode == null || outputMode.isEmpty() ? "summary" : outputMode;
        if (!"summary".equals(mode) && !"content".equals(mode)
                && !"files_with_matches".equals(mode) && !"count".equals(mode)) {
            return "错误：output_mode 只能是 summary、content、files_with_matches 或 count。";
        }
        if ("summary".equals(mode) && after != null) {
            return "错误：summary 是全局概览，没有 after 游标；请缩小 path/include 或使用 output_mode=content 续页。";
        }
        if (after != null && after.length() > 4096) return "错误：after 游标过长。";
        final Pattern matcher;
        try {
            matcher = Pattern.compile(Boolean.TRUE.equals(literal) ? Pattern.quote(pattern) : pattern);
        } catch (PatternSyntaxException e) {
            return "错误：无效正则表达式；修正 pattern，或使用 literal=true 做字面搜索。";
        }
        final PathMatcher includeMatcher;
        try {
            includeMatcher = include == null || include.isEmpty() ? null
                    : FileSystems.getDefault().getPathMatcher("glob:" + include.replace('\\', '/'));
        } catch (IllegalArgumentException e) {
            return "错误：无效的 include glob 模式。";
        }
        int pageSize = limit == null || limit < 1 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        int context = contextLines == null || contextLines < 1 ? 0 : Math.min(contextLines, 3);
        Path workPath = access.workPath(__cwd);
        final Path target;
        try {
            target = virtualResults ? toolResults.resolve(selectedPath, __sessionId, true)
                    : access.resolve(workPath, selectedPath);
        } catch (IOException e) {
            return "错误：当前会话中找不到该工具结果，或路径无效。";
        }
        if (!Files.exists(target)) return "错误：路径不存在。";
        Path policyRoot = virtualResults ? null : access.support.getSandboxPolicyRoot(workPath, selectedPath);
        PriorityQueue<Entry> page = new PriorityQueue<>(Comparator.comparing((Entry entry) -> entry.key).reversed());
        PriorityQueue<SummaryFile> summaries = new PriorityQueue<>(
                Comparator.comparingInt((SummaryFile file) -> file.matches)
                        .thenComparing((left, right) -> right.path.compareTo(left.path)));
        int[] summaryTotals = new int[2]; // matching files, matching lines
        int[] omitted = new int[3]; // oversized lines, oversized files, unreadable files
        Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return (!virtualResults && access.hiddenOrDenied(workPath, policyRoot, dir))
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile() || (!virtualResults && access.hiddenOrDenied(workPath, policyRoot, file))) {
                    return FileVisitResult.CONTINUE;
                }
                if (virtualResults) {
                    String filename = file.getFileName().toString();
                    if (!filename.matches("[a-f0-9]{64}\\.txt")) return FileVisitResult.CONTINUE;
                    try {
                        if (!file.equals(toolResults.resolve(ToolResultPaths.ROOT + "/" + filename,
                                __sessionId, false))) return FileVisitResult.CONTINUE;
                    } catch (IOException ignored) { return FileVisitResult.CONTINUE; }
                }
                if (includeMatcher != null && !includeMatcher.matches(file.getFileName())) {
                    return FileVisitResult.CONTINUE;
                }
                if (attrs.size() > MAX_FILE_BYTES) { omitted[1]++; return FileVisitResult.CONTINUE; }
                if (access.support.isNotTextFile(file)) return FileVisitResult.CONTINUE;
                String keyPath = SearchToolAccess.relative(target, file);
                String display = virtualResults ? ToolResultPaths.ROOT + "/" + file.getFileName()
                        : access.display(workPath, selectedPath, target, file);
                int matches = 0;
                List<String> samples = "summary".equals(mode) ? new ArrayList<>() : null;
                Deque<ContextLine> before = new ArrayDeque<>();
                List<Entry> pending = new ArrayList<>();
                try (PushbackReader reader = new PushbackReader(
                        new BufferedReader(Files.newBufferedReader(file, StandardCharsets.UTF_8), 8192), 1)) {
                    int number = 0;
                    Line line;
                    while ((line = readLine(reader, number == 0)) != null) {
                        number++;
                        String contextText = line.truncated ? "…(超长行未展示)" : snippet(line.text, 200);
                        for (Iterator<Entry> iterator = pending.iterator(); iterator.hasNext(); ) {
                            Entry entry = iterator.next();
                            entry.after.add("  +" + (number - entry.lineNumber) + " | " + contextText);
                            if (entry.after.size() >= context) iterator.remove();
                        }
                        if (line.truncated) {
                            omitted[0]++;
                            remember(before, new ContextLine(number, contextText), context);
                            continue;
                        }
                        Matcher match = matcher.matcher(line.text);
                        if (!match.find()) {
                            remember(before, new ContextLine(number, contextText), context);
                            continue;
                        }
                        matches++;
                        if ("summary".equals(mode)) {
                            if (samples.size() < SUMMARY_SAMPLES) {
                                samples.add(display + ":" + number + ": "
                                        + matchedSnippet(line.text, match.start(), match.end(), SUMMARY_SNIPPET_CHARS));
                            }
                            continue;
                        }
                        if ("files_with_matches".equals(mode)) break;
                        if ("content".equals(mode)) {
                            String key = keyPath + "#" + String.format("%010d", number);
                            if (after == null || key.compareTo(after) > 0) {
                                Entry entry = new Entry(key,
                                        display + ":" + number + ": "
                                                + matchedSnippet(line.text, match.start(), match.end(), MAX_SNIPPET_CHARS), number);
                                for (ContextLine earlier : before) {
                                    entry.before.add("  " + (earlier.number - number) + " | " + earlier.text);
                                }
                                offer(page, pageSize, entry);
                                if (context > 0) pending.add(entry);
                            }
                        }
                        remember(before, new ContextLine(number, contextText), context);
                    }
                } catch (IOException e) {
                    omitted[2]++;
                    return FileVisitResult.CONTINUE;
                }
                if ("summary".equals(mode) && matches > 0) {
                    summaryTotals[0]++;
                    summaryTotals[1] += matches;
                    summaries.offer(new SummaryFile(display, matches, samples));
                    if (summaries.size() > SUMMARY_FILES) summaries.poll();
                }
                if (matches > 0 && !"content".equals(mode)
                        && !"summary".equals(mode)
                        && (after == null || keyPath.compareTo(after) > 0)) {
                    String text = "count".equals(mode)
                            ? display + ": " + matches + " matching lines" : display;
                    offer(page, pageSize, new Entry(keyPath, text, 0));
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFileFailed(Path file, IOException error) {
                omitted[2]++;
                return FileVisitResult.CONTINUE;
            }
        });
        if ("summary".equals(mode)) return renderSummary(new ArrayList<>(summaries), summaryTotals, omitted);
        List<Entry> ordered = new ArrayList<>(page);
        ordered.sort(Comparator.comparing(entry -> entry.key));
        boolean hasMore = ordered.size() > pageSize;
        if (hasMore) ordered.remove(ordered.size() - 1);
        StringBuilder output = new StringBuilder();
        String lastKey = null;
        for (Entry entry : ordered) {
            String line = entry.render();
            if (output.length() + line.length() > MAX_BODY_CHARS) {
                hasMore = true;
                break;
            }
            output.append(line);
            lastKey = entry.key;
        }
        if (!ordered.isEmpty() && lastKey == null) return "错误：单个结果过长，无法在输出预算内展示。";
        if (ordered.isEmpty()) output.append(after == null ? "未找到结果。\n" : "(没有更多结果)\n");
        if (hasMore) {
            output.append("--- [内容未完] ---\n继续搜索请使用 after=")
                    .append(lastKey).append(", limit=").append(pageSize).append('\n');
        } else {
            output.append("--- [搜索完毕] ---\n");
        }
        if (omitted[0] + omitted[1] + omitted[2] > 0) {
            output.append("警告：搜索不完整；跳过超长行 ").append(omitted[0])
                    .append("、超过 10 MiB 的文件 ").append(omitted[1])
                    .append("、读取失败的文件 ").append(omitted[2]).append("。请缩小范围或另用工具核对。");
        }
        return output.toString();
    }

    private static String renderSummary(List<SummaryFile> files, int[] totals, int[] omitted) {
        files.sort(Comparator.comparingInt((SummaryFile file) -> file.matches).reversed()
                .thenComparing(file -> file.path));
        StringBuilder output = new StringBuilder("[搜索概览] 命中文件 ")
                .append(totals[0]).append(" 个，命中行 ").append(totals[1]).append(" 处。\n");
        int shown = 0;
        for (SummaryFile file : files) {
            if (shown >= SUMMARY_FILES) break;
            StringBuilder item = new StringBuilder("[FILE] ").append(file.path)
                    .append("（").append(file.matches).append(" 行）\n");
            for (String sample : file.samples) item.append("  ").append(sample).append('\n');
            if (output.length() + item.length() > MAX_BODY_CHARS - 512) break;
            output.append(item);
            shown++;
        }
        if (totals[0] > shown) output.append("仅展示 ").append(shown)
                .append(" 个文件；缩小 path/include 或使用 output_mode=content 查看其余命中。\n");
        int skipped = omitted[0] + omitted[1] + omitted[2];
        output.append(skipped == 0 ? "[搜索扫描完毕]\n" : "[搜索不完整]\n");
        if (skipped > 0) output.append("跳过超长行 ").append(omitted[0])
                .append("、超过 10 MiB 的文件 ").append(omitted[1])
                .append("、读取失败的文件 ").append(omitted[2]).append("；上述计数不包含这些内容。\n");
        output.append("用 read(file_path, offset, limit) 查看命中行附近代码；逐条结果用 output_mode=content。\n");
        return output.toString();
    }

    private static final class SummaryFile {
        final String path;
        final int matches;
        final List<String> samples;
        SummaryFile(String path, int matches, List<String> samples) {
            this.path = path;
            this.matches = matches;
            this.samples = samples;
        }
    }

    /**
     * 供直接调用方使用、保持旧签名兼容的入口。
     *
     * <p>检索 {@code @tool-results} 虚拟路径必须提供会话 ID，而本入口无法获得，
     * 故以空会话转发给完整重载（此时虚拟路径会返回错误文案）。</p>
     *
     * @param pattern      正则表达式；{@code literal=true} 时按字面匹配
     * @param path         搜索根目录，缺省为当前工作区 {@code .}
     * @param include      文件名 glob 过滤，可空
     * @param outputMode   {@code content}（默认）/ {@code files_with_matches} / {@code count}
     * @param contextLines content 模式下每条匹配前后各展示的行数，缺省 0、上限 3
     * @param after        续页游标，为空表示取首页
     * @param limit        单页最多返回项数，缺省 {@value #DEFAULT_LIMIT}、上限 {@value #MAX_LIMIT}
     * @param literal      {@code true} 时按字面字符串搜索，不将 pattern 当正则
     * @param __cwd        当前工作目录
     * @return 结果页文本；参数非法时返回 {@code 错误：…} 说明
     * @throws IOException 遍历目标路径失败时抛出
     */
    public String grep(String pattern, String path, String include, String outputMode,
                       Integer contextLines, String after, Integer limit, Boolean literal,
                       String __cwd) throws IOException {
        return grep(pattern, path, include, outputMode, contextLines, after, limit, literal, __cwd, null);
    }

    /**
     * 维护按键逆序最大堆：堆上限为 {@code size + 1}，多保留的一条用于探测“是否还有下一页”。
     *
     * @param page 当前页堆（堆顶为本页最大的键）
     * @param size 目标页大小（即 limit）
     * @param entry 待入堆的结果条目
     */
    static void offer(PriorityQueue<Entry> page, int size, Entry entry) {
        page.offer(entry);
        if (page.size() > size + 1) page.poll();
    }

    /**
     * 记录一行前后文，超出 {@code count} 时丢弃最旧的一行（保持最近 {@code count} 行）。
     *
     * @param before 上下文行双端队列
     * @param line   待记录的行
     * @param count  保留行数；为 0 时不记录
     */
    static void remember(Deque<ContextLine> before, ContextLine line, int count) {
        if (count == 0) return;
        before.addLast(line);
        if (before.size() > count) before.removeFirst();
    }

    /**
     * 截取展示片段：去除首尾空白，超过 {@code max} 字符时截断并标记 {@code …(片段已截断)}。
     *
     * @param text 原始行文本
     * @param max  最大展示字符数
     * @return 可安全展示的片段
     */
    static String snippet(String text, int max) {
        String trimmed = text.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) + "…(片段已截断)" : trimmed;
    }

    /**
     * 展示首个实际匹配及其附近正文，不以行首代替命中依据。
     * max 限制原文字符数；省略与位置说明另计，与普通 snippet 的截断标记保持一致。
     * 超预算匹配仅展示其头尾并明确标记；零宽匹配报告 UTF-16 字符位置。
     */
    static String matchedSnippet(String text, int matchStart, int matchEnd, int max) {
        String position = matchStart == matchEnd
                ? " [零宽命中，UTF-16 字符偏移=" + matchStart + "]" : "";
        if (text.length() <= max) return text + position;
        if (matchEnd - matchStart > max) {
            int headEnd = safeEnd(text, matchStart + max / 2);
            int tailStart = safeStart(text, matchEnd - (max - max / 2));
            return (matchStart > 0 ? "…" : "") + text.substring(matchStart, headEnd)
                    + "…(匹配过长，中间已省略)…" + text.substring(tailStart, matchEnd)
                    + (matchEnd < text.length() ? "…" : "")
                    + " [匹配 UTF-16 字符范围=" + matchStart + ".." + matchEnd + ")";
        }
        int padding = (max - (matchEnd - matchStart)) / 2;
        int start = Math.max(0, matchStart - padding);
        start = Math.min(start, Math.max(0, text.length() - max));
        // Moving a boundary inward avoids cutting surrogate pairs. The match itself
        // remains included; if it starts on a low surrogate, include its leading half.
        if (start > 0 && Character.isLowSurrogate(text.charAt(start))
                && Character.isHighSurrogate(text.charAt(start - 1))) {
            start += start < matchStart ? 1 : -1;
        }
        int end = safeEnd(text, Math.min(text.length(), Math.max(start + max, matchEnd)));
        return (start > 0 ? "…(前文已省略)" : "") + text.substring(start, end)
                + (end < text.length() ? "…(后文已省略)" : "") + position;
    }

    private static int safeEnd(String text, int end) {
        return end > 0 && end < text.length() && Character.isHighSurrogate(text.charAt(end - 1))
                && Character.isLowSurrogate(text.charAt(end)) ? end - 1 : end;
    }

    private static int safeStart(String text, int start) {
        return start > 0 && start < text.length() && Character.isLowSurrogate(text.charAt(start))
                && Character.isHighSurrogate(text.charAt(start - 1)) ? start + 1 : start;
    }

    /**
     * 按字符读取一行：兼容 {@code \r\n}、{@code \n}、{@code \r} 换行，首行跳过 BOM；
     * 超过 {@value #MAX_LINE_CHARS} 的部分丢弃，并将该行标记为超长（超长行不参与匹配）。
     *
     * @param reader 行读取来源
     * @param first  是否为首行（决定是否剥离 BOM）
     * @return 读到的行；已到流末尾且未读到任何字符时返回 {@code null}
     * @throws IOException 底层读取出错时抛出
     */
    static Line readLine(PushbackReader reader, boolean first) throws IOException {
        StringBuilder text = new StringBuilder();
        boolean seen = false, truncated = false;
        int value;
        while ((value = reader.read()) != -1) {
            seen = true;
            if (value == '\n') break;
            if (value == '\r') {
                int next = reader.read();
                if (next != '\n' && next != -1) reader.unread(next);
                break;
            }
            if (first && text.length() == 0 && value == '\uFEFF') continue;
            if (text.length() < MAX_LINE_CHARS) text.append((char) value);
            else truncated = true;
        }
        return seen ? new Line(text.toString(), truncated) : null;
    }

    /** 一次读入的原始行：{@code truncated=true} 表示超长部分已丢弃。 */
    private static final class Line {
        final String text;
        final boolean truncated;
        Line(String text, boolean truncated) { this.text = text; this.truncated = truncated; }
    }

    /**
     * 一条待分页的结果：{@code key} 用于排序与续页游标（content 模式为
     * {@code 文件路径#行号}，其余模式为文件路径），{@code before}/{@code after}
     * 保存前后文展示行。
     */
    private static final class Entry {
        /** 排序键，同时作为 after 续页游标。 */
        final String key, text;
        /** content 模式的匹配行号；其余模式为 0。 */
        final int lineNumber;
        /** 匹配行之前保留的上下文展示行。 */
        final List<String> before = new ArrayList<>();
        /** 匹配行之后保留的上下文展示行。 */
        final List<String> after = new ArrayList<>();
        Entry(String key, String text, int lineNumber) {
            this.key = key; this.text = text; this.lineNumber = lineNumber;
        }
        /** 按“前文 → 主行 → 后文”顺序渲染为多行文本。 */
        String render() {
            StringBuilder result = new StringBuilder();
            for (String line : before) result.append(line).append('\n');
            result.append(text).append('\n');
            for (String line : after) result.append(line).append('\n');
            return result.toString();
        }
    }

    /** 前后文中的一个行号及其展示文本。 */
    private static final class ContextLine {
        final int number;
        final String text;
        ContextLine(int number, String text) { this.number = number; this.text = text; }
    }
}
