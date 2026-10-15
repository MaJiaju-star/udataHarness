package org.noear.solon.ai.talents.cli;

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
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Bounded code search: selected result modes, stable paging, explicit omissions. */
public final class BoundedGrepTool extends AbsTalent {
    private static final int DEFAULT_LIMIT = 100;
    private static final int MAX_LIMIT = 500;
    private static final int MAX_BODY_CHARS = 12_000;
    private static final int MAX_LINE_CHARS = 16 * 1024;
    private static final int MAX_SNIPPET_CHARS = 1000;
    private static final long MAX_FILE_BYTES = 10L * 1024 * 1024;
    private final SearchToolAccess access;

    public BoundedGrepTool(TerminalTalent terminal, String workspace) {
        access = new SearchToolAccess(terminal, workspace);
    }

    @Override
    public String getInstruction(Prompt prompt) {
        return "grep 默认显示匹配行；只想定位文件时使用 output_mode=files_with_matches，统计分布时用 count。结果按路径/行号排序，可用 after 游标续页。";
    }

    @ToolMapping(name = "grep", description = "搜索文件内容。output_mode 可选 content（默认）、files_with_matches、count；content 可用 context 添加前后文。默认 100 项，最多 500 项，after 续页。")
    public String grep(
            @Param(value = "pattern", description = "正则表达式；若需字面搜索请设置 literal=true") String pattern,
            @Param(value = "path", required = false, description = "搜索根目录，默认工作区") String path,
            @Param(value = "include", required = false, description = "文件名 glob 模式，例如 *.{ts,tsx}") String include,
            @Param(value = "output_mode", required = false, description = "content、files_with_matches 或 count；默认 content") String outputMode,
            @Param(value = "context", required = false, description = "content 模式下每个匹配前后各展示几行，最多 3 行") Integer contextLines,
            @Param(value = "after", required = false, description = "相同 pattern/path/include/output_mode 的上一页 after 游标") String after,
            @Param(value = "limit", required = false, description = "单页结果数，默认 100，最大 500") Integer limit,
            @Param(value = "literal", required = false, description = "true 时按字面字符串搜索，不将 pattern 当正则") Boolean literal,
            String __cwd) throws IOException {
        if (pattern == null || pattern.isEmpty()) return "错误：pattern 不能为空。";
        if (pattern.length() > 1000) return "错误：pattern 过长（最多 1000 字符）。";
        String selectedPath = path == null || path.isEmpty() ? "." : path;
        if (access.isNonLocalVirtual(selectedPath)) {
            if (after != null || limit != null || contextLines != null || Boolean.TRUE.equals(literal)
                    || (outputMode != null && !outputMode.isEmpty() && !"content".equals(outputMode))) {
                return "错误：非本地逻辑挂载暂不支持 grep 的输出模式、前后文及分页参数；请缩小 path/include 后重试。";
            }
            return "提示：非本地逻辑挂载沿用 Solon 原始 grep，结果上限与续页行为不同，可能不完整。\n"
                    + access.terminal.grep(pattern, selectedPath, include, __cwd);
        }
        String mode = outputMode == null || outputMode.isEmpty() ? "content" : outputMode;
        if (!"content".equals(mode) && !"files_with_matches".equals(mode) && !"count".equals(mode)) {
            return "错误：output_mode 只能是 content、files_with_matches 或 count。";
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
        Path target = access.resolve(workPath, selectedPath);
        if (!Files.exists(target)) return "错误：路径不存在。";
        Path policyRoot = access.support.getSandboxPolicyRoot(workPath, selectedPath);
        PriorityQueue<Entry> page = new PriorityQueue<>(Comparator.comparing((Entry entry) -> entry.key).reversed());
        int[] omitted = new int[3]; // oversized lines, oversized files, unreadable files
        Files.walkFileTree(target, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                return access.hiddenOrDenied(workPath, policyRoot, dir)
                        ? FileVisitResult.SKIP_SUBTREE : FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (!attrs.isRegularFile() || access.hiddenOrDenied(workPath, policyRoot, file)) {
                    return FileVisitResult.CONTINUE;
                }
                if (includeMatcher != null && !includeMatcher.matches(file.getFileName())) {
                    return FileVisitResult.CONTINUE;
                }
                if (attrs.size() > MAX_FILE_BYTES) { omitted[1]++; return FileVisitResult.CONTINUE; }
                if (access.support.isNotTextFile(file)) return FileVisitResult.CONTINUE;
                String keyPath = SearchToolAccess.relative(target, file);
                String display = access.display(workPath, selectedPath, target, file);
                int matches = 0;
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
                        boolean hit = matcher.matcher(line.text).find();
                        if (!hit) {
                            remember(before, new ContextLine(number, contextText), context);
                            continue;
                        }
                        matches++;
                        if ("files_with_matches".equals(mode)) break;
                        if ("content".equals(mode)) {
                            String key = keyPath + "#" + String.format("%010d", number);
                            if (after == null || key.compareTo(after) > 0) {
                                Entry entry = new Entry(key,
                                        display + ":" + number + ": " + snippet(line.text, MAX_SNIPPET_CHARS), number);
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
                if (matches > 0 && !"content".equals(mode)
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

    private static void offer(PriorityQueue<Entry> page, int size, Entry entry) {
        page.offer(entry);
        if (page.size() > size + 1) page.poll();
    }

    private static void remember(Deque<ContextLine> before, ContextLine line, int count) {
        if (count == 0) return;
        before.addLast(line);
        if (before.size() > count) before.removeFirst();
    }

    private static String snippet(String text, int max) {
        String trimmed = text.trim();
        return trimmed.length() > max ? trimmed.substring(0, max) + "…(片段已截断)" : trimmed;
    }

    private static Line readLine(PushbackReader reader, boolean first) throws IOException {
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

    private static final class Line {
        final String text;
        final boolean truncated;
        Line(String text, boolean truncated) { this.text = text; this.truncated = truncated; }
    }

    private static final class Entry {
        final String key, text;
        final int lineNumber;
        final List<String> before = new ArrayList<>();
        final List<String> after = new ArrayList<>();
        Entry(String key, String text, int lineNumber) {
            this.key = key; this.text = text; this.lineNumber = lineNumber;
        }
        String render() {
            StringBuilder result = new StringBuilder();
            for (String line : before) result.append(line).append('\n');
            result.append(text).append('\n');
            for (String line : after) result.append(line).append('\n');
            return result.toString();
        }
    }

    private static final class ContextLine {
        final int number;
        final String text;
        ContextLine(int number, String text) { this.number = number; this.text = text; }
    }
}
