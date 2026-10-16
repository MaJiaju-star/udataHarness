package com.udata.harness.common.context;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Deterministic diagnostic projection; the captured original is stored first. */
final class ToolOutcomeProjection {
    private static final String ID = "0000000000000000000000000000000000000000000000000000000000000000";
    private static final Pattern EXIT_CODE = Pattern.compile("\\[exit_code=(-?\\d+)\\]");
    private static final Pattern DIAGNOSTIC = Pattern.compile(
            "(?i)(\\berror\\b|\\bfailed\\b|\\bfailure\\b|\\bfatal\\b|exception|traceback|caused by|assertion|\\[ERROR\\])");

    final String reason;
    private final String template;

    private ToolOutcomeProjection(String reason, String template) {
        this.reason = reason;
        this.template = template;
    }

    String render(String artifactId) {
        return template.replace(ID, artifactId);
    }

    static ToolOutcomeProjection plan(String tool, String content, boolean toolError, int budget) {
        if (content == null || content.length() <= budget || budget < 500) return null;
        boolean physicalCap = content.contains("字节的物理上限，命令已被终止");
        boolean timedOut = content.contains("执行超时：") || content.contains("已终止进程树");
        boolean preTruncated = content.contains("[输出过大已截断");
        boolean explicitFailure = content.startsWith("系统失败:") || content.startsWith("错误：");
        Matcher exitCode = EXIT_CODE.matcher(content);
        String code = exitCode.find() ? exitCode.group(1) : "unknown";
        String status = toolError ? "error" : physicalCap ? "output_limit"
                : timedOut ? "timeout" : explicitFailure
                || !"unknown".equals(code) && !"0".equals(code)
                ? "failed" : "completed";
        boolean captureComplete = !physicalCap && !timedOut && !preTruncated;
        String diagnostics = diagnosticLines(content);
        String prefix = "<tool-result-projection tool=\"" + safeTool(tool) + "\" status=\"" + status
                + "\" capture_complete=\"" + captureComplete + "\">\n"
                + "原始捕获字符数=" + content.length() + "; exit_code=" + code + "。"
                + (captureComplete ? "" : " 捕获本身不完整，恢复入口只能取得已捕获部分。") + "\n";
        String suffix = "\n[模型只看到诊断片段；完整已捕获快照可用 "
                + "read(file_path=\"@tool-results/" + ID
                + ".txt\") 分页查看；可用 grep 搜索]\n</tool-result-projection>";
        int remaining = budget - prefix.length() - suffix.length() - diagnostics.length() - 56;
        if (remaining < 0) {
            diagnostics = "诊断行较多，更多细节见完整捕获快照。\n";
            remaining = budget - prefix.length() - suffix.length() - diagnostics.length() - 56;
        }
        if (remaining < 0) return null;
        int each = Math.min(8000, remaining / 2);
        String head = safePrefix(content, each);
        String tail = safeSuffix(content, each);
        String template = prefix + diagnostics + "开头片段:\n" + head
                + "\n末尾片段:\n" + tail + suffix;
        if (template.length() > budget || template.length() >= content.length()) return null;
        return new ToolOutcomeProjection(toolError ? "long_error" : "bash_diagnostic", template);
    }

    private static String diagnosticLines(String content) {
        List<String> first = new ArrayList<>(3);
        Deque<String> last = new ArrayDeque<>(3);
        int matches = 0;
        int cursor = 0;
        while (cursor < content.length()) {
            int end = content.indexOf('\n', cursor);
            if (end < 0) end = content.length();
            String line = content.substring(cursor, end).trim();
            if (DIAGNOSTIC.matcher(line).find()) {
                matches++;
                String bounded = safePrefix(line, 240);
                if (first.size() < 3) first.add(bounded);
                else {
                    last.addLast(bounded);
                    if (last.size() > 3) last.removeFirst();
                }
            }
            cursor = end + 1;
        }
        if (matches == 0) return "";
        StringBuilder result = new StringBuilder("诊断行（共 ").append(matches).append(" 条命中）:\n");
        for (String line : first) result.append("- ").append(line).append('\n');
        if (matches > first.size() + last.size()) result.append("- …中间诊断行省略…\n");
        for (String line : last) result.append("- ").append(line).append('\n');
        return result.toString();
    }

    private static String safeTool(String tool) {
        return tool == null ? "unknown" : tool.replaceAll("[^a-zA-Z0-9_-]", "_");
    }

    private static String safePrefix(String text, int max) {
        int end = Math.min(text.length(), Math.max(0, max));
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }

    private static String safeSuffix(String text, int max) {
        int start = Math.max(0, text.length() - Math.max(0, max));
        if (start < text.length() && Character.isLowSurrogate(text.charAt(start))) start++;
        return text.substring(start);
    }
}
