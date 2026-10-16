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
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 为已完成的 {@code read} 工具结果规划有界、可恢复的面向模型投影。
 *
 * <p>当读取结果超出 {@link ContextManagementOptions#readVisibleChars} 预算时，
 * 依优先级尝试三种投影；任何方案都必须同时满足「不超预算」且「短于原文」才会被采纳：</p>
 * <ol>
 *   <li>{@code reference} —— 同路径、同规范参数、同内容的历史读取已完整可见时，
 *       仅引用其 {@code tool_call_id} 并给出归档快照的重读入口；</li>
 *   <li>{@code range_delta} —— 同文件同 SHA-256 版本下，只输出历史未覆盖的新增行，
 *       已覆盖且内容一致的行按行号区间引用；若同号行内容冲突则放弃该方案；</li>
 *   <li>{@code excerpt} —— 兜底：在行边界处截取前缀片段，并明确标注其不完整。</li>
 * </ol>
 * <p>每种投影都携带 {@code read(file_path="@tool-results/&lt;id&gt;.txt")} 形式的完整
 * 快照入口（渲染时以真实归档件 id 替换占位符 {@link #ID}），保证被压缩掉的内容始终可恢复；
 * 若所有方案都不可行则返回 {@code null}，交由调用方做通用截断兜底。</p>
 */
final class ReadToolProjection {
    /** 归档快照 id 占位符；{@link #render(String)} 时替换为真实归档件 id。 */
    private static final String ID = "0000000000000000000000000000000000000000000000000000000000000000";
    /** 行号前缀行：{@code  123 | 正文}。 */
    private static final Pattern NUMBERED_LINE = Pattern.compile("^\\s*(\\d+) \\| (.*)$");
    /** 兼容旧会话中的版本行；新结果仅从内部元数据读取版本。 */
    private static final Pattern VERSION = Pattern.compile("文件版本 SHA-256: ([0-9a-f]{64})");
    /** 续读提示中的 {@code offset=N}。 */
    private static final Pattern NEXT_OFFSET = Pattern.compile("offset=(\\d+)");

    /** 投影类型标记：{@code reference} / {@code range_delta} / {@code excerpt}，随结果一并留痕便于审计。 */
    final String type;

    /** 含 {@link #ID} 占位符的投影正文模板，见 {@link #render(String)}。 */
    final String template;

    /** 由 {@link #choose} 携带已判定的类型与正文创建。 */
    private ReadToolProjection(String type, String template) {
        this.type = type;
        this.template = template;
    }

    /**
     * 用真实归档件 id 渲染投影正文。
     *
     * @param artifactId 归档快照 id（64 位十六进制）
     * @return 替换占位符后的可发送正文
     */
    String render(String artifactId) {
        return template.replace(ID, artifactId);
    }

    /**
     * 依 {@code reference → range_delta → excerpt} 的优先级为本次读取选择投影。
     *
     * <p>返回 {@code null} 的情形：结果本就不超预算、所有候选方案均超预算或不比原文短、
     * 或增量比对出现同号行内容冲突 —— 此时由调用方走通用截断兜底。</p>
     *
     * @param trace   当前 ReAct 轨迹，用于枚举上下文中可见的历史 {@code read} 结果
     * @param current 本次已完成的读取交换，其参数提供文件路径与规范参数
     * @param content 本次读取的完整文本结果
     * @param budget  面向模型的字符预算（{@link ContextManagementOptions#readVisibleChars}）
     * @return 可渲染的投影；无需投影或方案不可行时为 {@code null}
     */
    static ReadToolProjection choose(ReActTrace trace, ToolExchanger current, String content, int budget) {
        List<VisibleRead> visible = visibleReads(trace);
        String path = path(current.getArgs());
        String args = canonical(current.getArgs());
        String version = version(content, current.getToolResult().metas());
        for (VisibleRead previous : visible) {
            if (path.equals(previous.path) && args.equals(previous.args)
                    && Objects.equals(version, previous.version)
                    && content.equals(previous.content) && content.length() > 300) {
                ReadWindow rangeWindow = ReadWindow.parse(content, version);
                String range = rangeWindow == null || rangeWindow.lines.isEmpty() ? ""
                        : "，行 " + rangeWindow.lines.keySet().iterator().next()
                        + "-" + last(rangeWindow.lines);
                String body = "<read-projection type=\"reference\">\n"
                        + "文件 " + display(path) + range + " 的本次读取与当前上下文中 tool_call_id="
                        + display(previous.callId) + " 的完整结果相同；请直接复用该结果。\n"
                        + "若该消息之后不可见，可用 read(file_path=\"@tool-results/" + ID
                        + ".txt\") 读取本次完整快照。\n"
                        + "</read-projection>";
                if (body.length() <= budget && body.length() < content.length()) {
                    return new ReadToolProjection("reference", body);
                }
            }
        }

        ReadWindow now = ReadWindow.parse(content, version);
        if (now != null && now.version != null && !now.lines.isEmpty()) {
            Map<Integer, String> covered = new HashMap<>();
            List<String> sourceIds = new ArrayList<>();
            boolean overlap = false;
            for (VisibleRead previous : visible) {
                if (!path.equals(previous.path)) continue;
                ReadWindow old = ReadWindow.parse(previous.content, previous.version);
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
                        .append("（同一文件版本）。\n")
                        .append("复用来源 tool_call_id=").append(String.join(",", sourceIds)).append("。\n")
                        .append("当前上下文中可复用的完整行：").append(ranges(reused)).append("。\n")
                        .append("本次未复用的行（单行截断标记仍表示该行不完整）：\n")
                        .append(fresh);
                Matcher next = NEXT_OFFSET.matcher(content);
                if (content.contains("[内容未完]") && next.find()) {
                    body.append("本次文件读取尚未结束；续读 offset=").append(next.group(1)).append("。\n");
                }
                body.append("完整快照：read(file_path=\"@tool-results/").append(ID)
                        .append(".txt\")。\n</read-projection>");
                if (body.length() <= budget && body.length() < content.length()) {
                    return new ReadToolProjection("range_delta", body.toString());
                }
            }
        }

        if (content.length() <= budget) return null; // full, no projection needed
        String prefix = "<read-projection type=\"excerpt\">\n"
                + "以下仅为读取结果片段，不代表完整文件；需用下方 read 读取本次完整快照，"
                + "若需当前文件则重新 read。\n";
        String suffix = "\n[片段到此结束；其余内容未显示]\n完整快照：read(file_path=\"@tool-results/"
                + ID + ".txt\")。\n</read-projection>";
        int allowance = Math.max(0, budget - prefix.length() - suffix.length());
        String excerpt = headAtLineBoundary(content, allowance);
        String output = prefix + excerpt + suffix;
        if (output.length() > budget || output.length() >= content.length()) return null;
        return new ReadToolProjection("excerpt", output);
    }

    /** 取行号有序映射的最后一个行号；调用方保证映射非空。 */
    private static int last(Map<Integer, String> lines) {
        int value = 0;
        for (int key : lines.keySet()) value = key;
        return value;
    }

    /**
     * 把升序去重的行号压缩为紧凑区间文本，如 {@code 1-3,7,9-10}。
     *
     * @param values 升序行号；为空时返回 {@code (无)}
     */
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

    /**
     * 截取不超过 {@code max} 字符的前缀：优先止于换行符，避免把一行截成半行，
     * 也不会切断代理对（surrogate pair）。
     *
     * @param text 原文
     * @param max  字符上限
     * @return 安全的前缀正文
     */
    private static String headAtLineBoundary(String text, int max) {
        int end = Math.min(max, text.length());
        int newline = text.lastIndexOf('\n', end - 1);
        if (newline > 0) return text.substring(0, newline + 1);
        if (end > 0 && Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }

    /** 面向模型展示的参数值：回车换行转义并截断到 180 字符，防止破坏投影正文结构。 */
    private static String display(String value) {
        String safe = value == null ? "" : value.replace("\r", "\\r").replace("\n", "\\n");
        return safe.length() > 180 ? safe.substring(0, 180) + "…" : safe;
    }

    /** 取工具参数中的 {@code file_path}；缺失或非字符串时返回空串。 */
    private static String path(Map<String, Object> args) {
        Object value = args == null ? null : args.get("file_path");
        return value instanceof String ? (String) value : "";
    }

    /**
     * 规范化工具参数：剔除 {@code __} 前缀的内部元数据键，按键排序后序列化，
     * 用于判定两次读取的参数是否完全一致。
     */
    private static String canonical(Map<String, Object> args) {
        Map<String, Object> selected = new java.util.TreeMap<>();
        if (args != null) args.forEach((key, value) -> {
            if (!key.startsWith("__")) selected.put(key, value);
        });
        return ONode.serialize(selected);
    }

    /**
     * 枚举当前上下文中可复用的历史 {@code read} 结果，按工作记忆中的出现顺序排列。
     *
     * <p>仅接受成功的、纯文本的、且未被归档件或投影包装过的结果；错误、多模态、
     * 直接返回，以及 {@code context-artifact} / {@code read-projection} 形式的消息一律排除，
     * 避免对投影再次投影造成递归引用。</p>
     *
     * @param trace 当前 ReAct 轨迹；其工作记忆缺失时返回空列表
     */
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
                        canonical(call.getArguments()), tool.getContent(),
                        version(tool.getContent(), tool.getMetadata())));
            }
        }
        return found;
    }

    /** 一条可复用的历史读取：调用 id、文件路径、规范参数与结果原文。 */
    private static final class VisibleRead {
        final String callId, path, args, content, version;
        VisibleRead(String callId, String path, String args, String content, String version) {
            this.callId = callId; this.path = path; this.args = args; this.content = content;
            this.version = version;
        }
    }

    /** 优先使用读取时的内部版本，缺失时兼容旧会话正文中的版本行。 */
    private static String version(String content, Map<String, ?> metadata) {
        Object value = metadata.get("sourceHash");
        if (value instanceof String) return (String) value;
        Matcher match = VERSION.matcher(content);
        return match.find() ? match.group(1) : null;
    }

    /** 从 read 结果中解析出的行窗口：文件版本（可缺失）与「行号 → 正文」映射。 */
    private static final class ReadWindow {
        final String version;
        final Map<Integer, String> lines;
        private ReadWindow(String version, Map<Integer, String> lines) {
            this.version = version; this.lines = lines;
        }
        /**
         * 解析带行号前缀的 read 结果（以 {@code --- File: ...} 头与分隔线开头）。
         *
         * @param content 工具结果原文
         * @param version 读取时的文件版本，仅在后端使用
         * @return 行窗口；非标准文件头或行号无法解析时为 {@code null}
         */
        static ReadWindow parse(String content, String version) {
            if (!content.startsWith("--- File: ") || !content.contains("--------------------------------------------------\n")) return null;
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
