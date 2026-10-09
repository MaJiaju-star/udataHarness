package com.udata.harness.common.context;

import org.noear.solon.ai.chat.message.*;
import java.util.*;
import java.util.regex.*;

/**
 * 读取范围增量复用：把一次完整 {@code read} 结果压缩成“仅保留新行”的增量视图。
 *
 * <p>复用的前提是“行号连续 + 与活跃上下文中可见行完全一致”：只有当前请求之前、同一路径、同一工作区
 * （{@code _context_cwd}）的 {@code read} 结果里真实可见过的整行，才允许被省略。任何一行缺失、乱序或
 * 超出声明范围都会让本次投影整体放弃（返回 {@code null}），由调用方回退到普通片段策略。</p>
 *
 * <p>本类只做“模型可见正文”的缩减，不跳过任何 IO：工具调用照常执行，完整原文由
 * {@link ContextArtifactStore} 先行归档，被省略的内容仍可通过 {@code context_restore} 恢复。</p>
 *
 * <p>不把“引用”当作证据：被判定为复用的行必须来自可见正则解析出的行，引用链不能作为补齐缺失行的来源，
 * 因此不会从旧投影推断出从未真正展示过的内容。</p>
 */
final class ReadRangeReuse {
    /**
     * 工具结果元数据键：读取文件路径，用于判断两次读取是否针对同一文件。
     */
    static final String PATH = "_udata_read_path", SCOPE = "_udata_read_scope";
    /**
     * 带行号正文行的匹配模式：{@code <空格><行号> | <内容>}，按多行模式逐行扫描。
     */
    private static final Pattern LINE = Pattern.compile("(?m)^ *([0-9]{1,18}) \\| ([^\\r\\n]*)(?:\\r?\\n)");
    /**
     * 读取结果头部声明的匹配模式：提取文件路径、起始行与结束行。
     */
    private static final Pattern HEADER = Pattern.compile("^--- File: (.+) \\(Lines: ([0-9]{1,18}) - ([0-9]{1,18}), Size: [^\\n]+\\) ---\\r?\\n");

    /**
     * 从工具参数中取出目标文件路径。
     *
     * @param args 工具调用参数
     * @return 文件路径字符串；参数缺失或类型不符时返回 {@code null}
     */
    static String path(Map<String,Object> args) {
        Object path = args.get("file_path");
        return path instanceof String ? (String) path : null;
    }

    /**
     * 从工具参数中取出工作区作用域，用于隔离不同工作目录下的同名文件。
     *
     * @param args 工具调用参数
     * @return 作用域标识；未携带时返回空串
     */
    static String scope(Map<String,Object> args) { return String.valueOf(args.getOrDefault("_context_cwd", "")); }

    /**
     * 生成“仅含新行”的读取增量视图，无法安全复用或收益不足时返回 {@code null}。
     *
     * <p>判定顺序：先校验头部与声明范围，再收集当前请求之前可见的旧行，最后按行号连续性逐行比对。
     * 只有整体满足“行号从声明起点连续到声明终点”、且至少复用一行、且结果确实更短且不超预算时才返回内容。</p>
     *
     * @param original 本次完整读取结果
     * @param args     本次读取的工具参数
     * @param active   当前活跃上下文，仅用于回溯本次请求之前的可见行
     * @param id       已归档的完整原文证据 ID，写入结果头部供后续恢复
     * @param maxChars 投影可见字符预算
     * @return 增量正文；不可复用或压缩无收益时返回 {@code null}
     */
    static String delta(ToolMessage original, Map<String,Object> args, List<ChatMessage> active, String id, int maxChars) {
        //1. 校验前置条件：必须能定位文件路径，且头部声明的路径与参数路径一致，否则不足以判断“同一范围”。
        String path = path(args), text = original.getContent();
        Matcher header = HEADER.matcher(text);
        if (path == null || !header.find() || !path.equals(header.group(1))) return null;
        long expected = Long.parseLong(header.group(2)), declaredEnd = Long.parseLong(header.group(3));
        //2. 收集本次请求之前、同路径同作用域的可见整行；单行被截断的行不作为可复用证据。
        Map<Long,String> visible = new HashMap<>();
        for (ChatMessage message : active) {
            if (message == original) break; // Do not reference future messages in this request.
            if (!(message instanceof ToolMessage) || !"read".equals(((ToolMessage) message).getName())
                    || !path.equals(message.getMetadataAs(PATH)) || !scope(args).equals(message.getMetadataAs(SCOPE))) continue;
            Matcher old = LINE.matcher(message.getContent());
            while (old.find()) if (!old.group(2).contains("单行过长已截断")) visible.put(Long.parseLong(old.group(1)), old.group(2));
        }
        //3. 按行号连续性扫描本次正文，边比对边拼接：复用段收敛为一行标记，其余原文原样保留。
        Matcher lines = LINE.matcher(text);
        StringBuilder result = new StringBuilder("[Fresh read; matching lines reused from visible context only. File-wide version is not inferred. Artifact: "
                + id + "; context_restore recovers complete output.]\n");
        int cursor = 0, count = 0; long start = -1, end = -1;
        while (lines.find()) {
            long number = Long.parseLong(lines.group(1));
            //3.1 行号必须从声明起点开始逐行递增，且不越过声明终点；否则本次投影不可信。
            if (number != expected++ || number > declaredEnd) return null;
            //3.2 仅当该行未被截断、且与可见上下文中的同号行完全一致时才判定为可复用。
            boolean same = !lines.group(2).contains("单行过长已截断") && lines.group(2).equals(visible.get(number));
            if (same) {
                //3.3 进入复用段：段首记录原文位置与起始行号，段内逐行延伸，暂不输出正文。
                if (start < 0) { result.append(text, cursor, lines.start()); start = number; }
                end = number; count++; cursor = lines.end();
            } else {
                //3.4 遇不可复用行：先闭合前一复用段并写出标记，再原样输出该行。
                if (start >= 0) { result.append("[reused visible lines ").append(start).append('-').append(end).append("]\n"); start = -1; }
                result.append(text, cursor, lines.end()); cursor = lines.end();
            }
        }
        //4. 收尾校验：行号必须恰好覆盖到声明终点，未闭合的复用段补上尾部标记，再接回头部/尾部等非行号内容。
        if (expected - 1 != declaredEnd) return null;
        if (start >= 0) result.append("[reused visible lines ").append(start).append('-').append(end).append("]\n");
        result.append(text.substring(cursor));
        //5. 收益门槛：必须至少复用一行、确实比原文短且不超预算，否则交由调用方走普通片段策略。
        return count > 0 && result.length() < text.length() && result.length() <= maxChars ? result.toString() : null;
    }
}
