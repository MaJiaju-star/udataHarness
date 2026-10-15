package com.udata.harness.common.context;

import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.tool.ToolCall;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Plans a bounded, recoverable model-facing projection of a completed read. */
final class ReadToolProjection {
    private static final String ID = "0000000000000000000000000000000000000000000000000000000000000000";
    private static final Pattern NUMBERED_LINE = Pattern.compile("^\\s*(\\d+) \\| (.*)$");
    private static final Pattern VERSION = Pattern.compile("文件版本 SHA-256: ([0-9a-f]{64})");
    private static final Pattern NEXT_OFFSET = Pattern.compile("offset=(\\d+)");

    final String type;
    final String template;

    private ReadToolProjection(String type, String template) {
        this.type = type;
        this.template = template;
    }

    String render(String artifactId) {
        return template.replace(ID, artifactId);
    }

    static ReadToolProjection choose(ReActTrace trace, ToolExchanger current, String content, int budget) {
        List<VisibleRead> visible = visibleReads(trace);
        String path = path(current.getArgs());
        String args = canonical(current.getArgs());
        for (VisibleRead previous : visible) {
            if (path.equals(previous.path) && args.equals(previous.args)
                    && content.equals(previous.content) && content.length() > 300) {
                ReadWindow rangeWindow = ReadWindow.parse(content);
                String range = rangeWindow == null || rangeWindow.lines.isEmpty() ? ""
                        : "，行 " + rangeWindow.lines.keySet().iterator().next()
                        + "-" + last(rangeWindow.lines);
                String body = "<read-projection type=\"reference\">\n"
                        + "文件 " + display(path) + range + " 的本次读取与当前上下文中 tool_call_id="
                        + display(previous.callId) + " 的完整结果相同；请直接复用该结果。\n"
                        + "若该消息之后不可见，可用 context_restore(artifact_id=\"" + ID
                        + "\", offset=0, limit=4000) 恢复本次完整快照。\n"
                        + "</read-projection>";
                if (body.length() <= budget && body.length() < content.length()) {
                    return new ReadToolProjection("reference", body);
                }
            }
        }

        ReadWindow now = ReadWindow.parse(content);
        if (now != null && now.version != null && !now.lines.isEmpty()) {
            Map<Integer, String> covered = new HashMap<>();
            List<String> sourceIds = new ArrayList<>();
            boolean overlap = false;
            for (VisibleRead previous : visible) {
                if (!path.equals(previous.path)) continue;
                ReadWindow old = ReadWindow.parse(previous.content);
                if (old == null || !now.version.equals(old.version)) continue;
                if (sourceIds.size() < 3) sourceIds.add(display(previous.callId));
                for (Map.Entry<Integer, String> line : old.lines.entrySet()) {
                    if (!line.getValue().contains("…(单行已截断)")) {
                        covered.put(line.getKey(), line.getValue());
                    }
                }
            }
            StringBuilder fresh = new StringBuilder();
            List<Integer> reused = new ArrayList<>();
            boolean conflict = false;
            for (Map.Entry<Integer, String> line : now.lines.entrySet()) {
                String old = covered.get(line.getKey());
                if (old != null) {
                    if (!old.equals(line.getValue())) { conflict = true; break; }
                    overlap = true;
                    reused.add(line.getKey());
                } else {
                    fresh.append(String.format("%6d | %s\n", line.getKey(), line.getValue()));
                }
            }
            if (overlap && !conflict) {
                String range = now.lines.keySet().iterator().next() + "-" + last(now.lines);
                StringBuilder body = new StringBuilder("<read-projection type=\"range_delta\">\n")
                        .append("文件 ").append(display(path)).append("，行 ").append(range)
                        .append("，SHA-256=").append(now.version).append("。\n")
                        .append("复用来源 tool_call_id=").append(String.join(",", sourceIds)).append("。\n")
                        .append("当前上下文中可复用的完整行：").append(ranges(reused)).append("。\n")
                        .append("本次未复用的行（单行截断标记仍表示该行不完整）：\n")
                        .append(fresh);
                Matcher next = NEXT_OFFSET.matcher(content);
                if (content.contains("[内容未完]") && next.find()) {
                    body.append("本次文件读取尚未结束；续读 offset=").append(next.group(1)).append("。\n");
                }
                body.append("完整快照：context_restore(artifact_id=\"").append(ID)
                        .append("\", offset=0, limit=4000)。\n</read-projection>");
                if (body.length() <= budget && body.length() < content.length()) {
                    return new ReadToolProjection("range_delta", body.toString());
                }
            }
        }

        if (content.length() <= budget) return null; // full, no projection needed
        String prefix = "<read-projection type=\"excerpt\">\n"
                + "以下仅为读取结果片段，不代表完整文件；需用下方 context_restore 读取本次完整快照，"
                + "若需当前文件则重新 read。\n";
        String suffix = "\n[片段到此结束；其余内容未显示]\n完整快照：context_restore(artifact_id=\""
                + ID + "\", offset=0, limit=4000)。\n</read-projection>";
        int allowance = Math.max(0, budget - prefix.length() - suffix.length());
        String excerpt = headAtLineBoundary(content, allowance);
        String output = prefix + excerpt + suffix;
        if (output.length() > budget || output.length() >= content.length()) return null;
        return new ReadToolProjection("excerpt", output);
    }

    private static int last(Map<Integer, String> lines) {
        int value = 0;
        for (int key : lines.keySet()) value = key;
        return value;
    }

    private static String ranges(List<Integer> values) {
        if (values.isEmpty()) return "(无)";
        StringBuilder result = new StringBuilder();
        int start = values.get(0), end = start;
        for (int i = 1; i < values.size(); i++) {
            int value = values.get(i);
            if (value == end + 1) { end = value; continue; }
            if (result.length() > 0) result.append(',');
            result.append(start).append(start == end ? "" : "-" + end);
            start = end = value;
        }
        if (result.length() > 0) result.append(',');
        return result.append(start).append(start == end ? "" : "-" + end).toString();
    }

    private static String headAtLineBoundary(String text, int max) {
        int end = Math.min(max, text.length());
        int newline = text.lastIndexOf('\n', end - 1);
        if (newline > 0) return text.substring(0, newline + 1);
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }

    private static String display(String value) {
        String safe = value == null ? "" : value.replace("\r", "\\r").replace("\n", "\\n");
        return safe.length() > 180 ? safe.substring(0, 180) + "…" : safe;
    }

    private static String path(Map<String, Object> args) {
        Object value = args == null ? null : args.get("file_path");
        return value instanceof String ? (String) value : "";
    }

    private static String canonical(Map<String, Object> args) {
        Map<String, Object> selected = new java.util.TreeMap<>();
        if (args != null) args.forEach((key, value) -> {
            if (!key.startsWith("__")) selected.put(key, value);
        });
        return ONode.serialize(selected);
    }

    private static List<VisibleRead> visibleReads(ReActTrace trace) {
        List<VisibleRead> found = new ArrayList<>();
        if (trace.getWorkingMemory() == null) return found;
        Map<String, ToolCall> calls = new HashMap<>();
        for (ChatMessage message : trace.getWorkingMemory().getMessages()) {
            if (message instanceof AssistantMessage) {
                List<ToolCall> items = ((AssistantMessage) message).getToolCalls();
                if (items != null) for (ToolCall item : items) calls.put(item.getId(), item);
            } else if (message instanceof ToolMessage) {
                ToolMessage tool = (ToolMessage) message;
                ToolCall call = calls.get(tool.getToolCallId());
                if (call == null || !"read".equals(call.getName()) || tool.isError()
                        || !"read".equals(tool.getName()) || tool.isReturnDirect()
                        || tool.isMultiModal()
                        || !tool.getBlocks().stream().allMatch(block -> block instanceof TextBlock)
                        || tool.getContent() == null
                        || tool.getContent().startsWith("<context-artifact")
                        || tool.getContent().startsWith("<read-projection")) continue;
                found.add(new VisibleRead(tool.getToolCallId(), path(call.getArguments()),
                        canonical(call.getArguments()), tool.getContent()));
            }
        }
        return found;
    }

    private static final class VisibleRead {
        final String callId, path, args, content;
        VisibleRead(String callId, String path, String args, String content) {
            this.callId = callId; this.path = path; this.args = args; this.content = content;
        }
    }

    private static final class ReadWindow {
        final String version;
        final Map<Integer, String> lines;
        private ReadWindow(String version, Map<Integer, String> lines) {
            this.version = version; this.lines = lines;
        }
        static ReadWindow parse(String content) {
            if (!content.startsWith("--- File: ") || !content.contains("--------------------------------------------------\n")) return null;
            Matcher versionMatch = VERSION.matcher(content);
            String version = versionMatch.find() ? versionMatch.group(1) : null;
            String separator = "--------------------------------------------------\n";
            String afterHeader = content.substring(content.indexOf(separator) + separator.length());
            Map<Integer, String> lines = new LinkedHashMap<>();
            for (String row : afterHeader.split("\n")) {
                Matcher match = NUMBERED_LINE.matcher(row);
                if (!match.matches()) break;
                try { lines.put(Integer.parseInt(match.group(1)), match.group(2)); }
                catch (NumberFormatException ignored) { return null; }
            }
            return new ReadWindow(version, lines);
        }
    }
}
