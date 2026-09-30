package com.udata.harness.common.context;

import org.noear.solon.ai.chat.message.*;
import org.noear.solon.ai.chat.tool.ToolResult;

import java.util.*;
import java.util.regex.Pattern;

/**
 * 工具结果投影策略：把新产生的纯文本证据替换为有界片段，并保证原文已先行归档。
 *
 * <p>只治理支持列表中工具的普通文本结果；多模态内容、直返结果、已投影消息一律原样返回，
 * 因此已发送过的消息保持<b>字节稳定</b>，重复执行治理不会反复改写历史。</p>
 *
 * <p>投影分三种：{@code full}（短结果保留全文）、{@code reference}（同版本同范围读取已在活跃上下文中，
 * 仅返回引用）、{@code excerpt}（长结果按策略保留头/诊断/尾并标注不完整）。
 * 若引用文字比短正文还长，则直接保留正文，避免去重反而增加请求载荷。</p>
 *
 * <p>片段可能遗漏未命中诊断关键词的信息，因此片段<b>只是部分证据</b>；
 * 模型应把不完整输出当线索，必要时用 {@code context_restore} 取回原文或重新执行工具。</p>
 */
public class ToolResultPolicy {
    /**
     * 证据 ID 元数据键，指向归档中的完整原文。
     */
    public static final String ARTIFACT = "_udata_artifact_id";

    /**
     * 投影类型元数据键：{@code full}、{@code reference} 或 {@code excerpt}。
     */
    public static final String PROJECTION = "_udata_projection";

    /**
     * 投影规则元数据键：{@code source}、{@code diagnostics}、{@code matches} 或 {@code generic}。
     */
    public static final String POLICY = "_udata_projection_policy";

    /**
     * 参与治理的工具白名单；其余工具保持框架原有行为。
     */
    private static final Set<String> SUPPORTED = new HashSet<>(Arrays.asList(
            "read", "grep", "glob", "ls", "list", "webfetch", "websearch", "codesearch", "bash", "bash_wait"));

    /**
     * 重要行特征：包含成功/失败/测试结果等关键进展，作为诊断行的宽匹配。
     */
    private static final Pattern IMPORTANT = Pattern.compile(
            "(?i)(error|exception|failed|failure|fatal|caused by|assertion|tests run|tests? passed|exit.?code|build (success|failure)|失败|错误|异常|通过)");

    /**
     * 失败行特征：比 IMPORTANT 更严格，优先级更高，用于区分“失败”与“完成”。
     */
    private static final Pattern FAILURE = Pattern.compile("(?i)(error|exception|failed|failure|fatal|caused by|assertion|失败|错误|异常)");

    /**
     * 单条投影的可见字符上限，取值 1000..16000。
     */
    private final int maxChars;

    /**
     * 创建投影策略。
     *
     * @param maxChars 单条工具结果可投影的最大字符数，取值 1000..16000
     * @throws IllegalArgumentException 超出取值范围时抛出
     */
    public ToolResultPolicy(int maxChars) {
        if (maxChars < 1000 || maxChars > 16000) throw new IllegalArgumentException("Tool result max chars must be 1000..16000");
        this.maxChars = maxChars;
    }

    /**
     * 把一条工具结果投影为有界片段，并回填证据元数据。
     *
     * <p>不参与治理或已投影的结果直接返回原对象（调用方用引用比较判断是否变化）。
     * 原文总是先归档再返回更小的投影：即使投影失败，完整输出也不会丢。</p>
     *
     * @param original  原始工具结果
     * @param args      对应工具调用的参数，用于判断是否为同一读取范围
     * @param active    当前活跃上下文，用于判断旧全文是否仍在
     * @param artifacts 证据归档，用于写入原文并生成 ID
     * @return 投影后的工具结果；无需治理时返回 {@code original} 本身
     */
    public ToolMessage project(ToolMessage original, Map<String, Object> args,
                               List<ChatMessage> active, ContextArtifactStore artifacts) {
        //1. 不支持的工具、多模态内容、直返结果、已投影消息以及无正文结果，一律保持框架原有行为。
        if (!SUPPORTED.contains(original.getName()) || original.isMultiModal()
                || original.isReturnDirect() || original.hasMetadata(PROJECTION) || original.getContent() == null) return original;
        String text = original.getContent();
        //2. 先把完整原文写入归档，再考虑缩减：投影失败也不会丢证据。
        String id = artifacts.put(original.getName(), args, text); // Commit raw output before returning a smaller projection.
        String reference = "Same file version and read range are already present in active context. Artifact: " + id
                + ". Use context_restore if the earlier content is no longer available.";
        //3. 仅当“同版本同范围的 read 全文”仍在活跃上下文且引用确实更短时，才降级为引用。
        boolean duplicate = "read".equals(original.getName()) && text.length() > reference.length() && active.stream().anyMatch(m -> m != original
                && id.equals(m.getMetadataAs(ARTIFACT)) && "full".equals(m.getMetadataAs(PROJECTION)));
        String kind = duplicate ? "reference" : text.length() > maxChars ? "excerpt" : "full";
        //4. 按工具选择片段规则：文件源码、bash 日志、匹配结果与其他文本分别处理。
        String policy = "read".equals(original.getName()) ? "source"
                : Arrays.asList("bash", "bash_wait").contains(original.getName()) ? "diagnostics"
                : Arrays.asList("grep", "glob", "ls", "list", "websearch", "codesearch").contains(original.getName()) ? "matches" : "generic";
        String visible = text;
        if (duplicate) {
            visible = reference;
        } else if ("excerpt".equals(kind)) {
            //5. 长结果加头部说明（含原文长度、证据 ID 与恢复方式），再拼接有界片段。
            String header = "[Partial tool output; " + text.length() + " original characters. Artifact: " + id
                    + ". Use context_restore(id, offset, maxChars) to recover omitted evidence.]\n";
            visible = header + ("matches".equals(policy) ? matches(text, maxChars - header.length())
                    : "source".equals(policy) ? source(text, maxChars - header.length())
                    : excerpt(text, maxChars - header.length()));
        }
        //6. 保留原元数据，并回填证据 ID、投影类型与规则，供后续判断与前端展示。
        ToolResult result = ToolResult.success(visible);
        result.metas().putAll(original.getMetadata());
        result.metas().put(ARTIFACT, id);
        result.metas().put(PROJECTION, kind);
        result.metas().put(POLICY, policy);
        ToolMessage projected = new ToolMessage(result, original.getName(), original.getToolCallId(), false);
        return projected;
    }

    /**
     * 文件正文片段：保留按原顺序排列的头尾两段，中间标记为省略。
     *
     * <p>对源码做均分截断，不把代码里的 error 字样当日志诊断，也不推断代码是否可编译。</p>
     *
     * @param text   完整正文
     * @param budget 可见字符预算，已扣除头部说明的长度
     * @return 头部 + 省略标记 + 尾部的片段
     */
    private String source(String text, int budget) {
        String marker = "\n[omitted source; use context_restore for exact intervening code]\n";
        int head = (budget - marker.length()) / 2;
        int tail = budget - marker.length() - head;
        return text.substring(0, head) + marker + text.substring(text.length() - tail);
    }

    /**
     * 匹配结果片段：保留完整的首部匹配行，并保留其路径/行号标识。
     *
     * <p>在预算内取最后一个完整换行，避免切断半行；未知的单行/JSON 布局退化为有界片段，
     * 不做任何相关性排序或打分。</p>
     *
     * @param text   完整正文
     * @param budget 可见字符预算，已扣除头部说明的长度
     * @return 首部完整行 + 省略标记；无法按行切分时退化为 {@link #source}
     */
    private String matches(String text, int budget) {
        String marker = "\n[additional matches omitted; restore artifact for complete results]\n";
        int available = budget - marker.length();
        int end = text.lastIndexOf('\n', available);
        if (end <= 0) return source(text, budget); // Unknown single-line/JSON layout: explicit bounded fallback.
        return text.substring(0, end) + marker;
    }

    /**
     * 通用文本片段：保留头部、带原始偏移的诊断行以及尾部。
     *
     * <p>诊断行按“失败行优先、再补状态行”的顺序选取，并在每行前标注原始字符偏移，
     * 使遗漏的报错、代码或日志仍可通过恢复工具取回。
     * <b>绝不暗示这是一份完整摘要</b>：片段中会显式插入省略标记。</p>
     *
     * @param text   完整正文
     * @param budget 可见字符预算，已扣除头部说明的长度
     * @return 头部 + 诊断行 + 尾部的片段
     */
    private String excerpt(String text, int budget) {
        //1. 先切出头部与尾部，中间部分留给诊断行。
        int head = budget / 4;
        int tail = budget / 3;
        StringBuilder middle = new StringBuilder("\n[omitted middle; diagnostic excerpts below]\n");
        int middleBudget = budget - head - tail - 100;
        int middleEnd = text.length() - tail;
        //2. 两轮扫描：先失败行（FAILURE），再状态/进展行（IMPORTANT）；重复行不取两次。
        // Reserve diagnostic budget for failures before success/progress lines, keeping original offsets.
        for (Pattern priority : Arrays.asList(FAILURE, IMPORTANT)) {
            int position = head;
            while (position < middleEnd && middle.length() < middleBudget) {
                int lineEnd = text.indexOf('\n', position);
                if (lineEnd < 0 || lineEnd > middleEnd) lineEnd = middleEnd;
                String line = text.substring(position, lineEnd);
                java.util.regex.Matcher match = priority.matcher(line);
                //3. IMPORTANT 轮跳过已在失败行中处置过的行，避免同一行重复占用预算。
                if (priority == IMPORTANT && FAILURE.matcher(line).find()) { position = lineEnd + 1; continue; }
                if (match.find()) {
                    //4. 超长行以命中位置为中心截取，并标注该片段在原文中的绝对偏移。
                    int diagnosticStart = line.length() > 500 ? Math.max(0, match.start() - 100) : 0;
                    String diagnostic = line.substring(diagnosticStart, Math.min(line.length(), diagnosticStart + 500));
                    String snippet = "[offset " + (position + diagnosticStart) + "] " + diagnostic + "\n";
                    int remaining = middleBudget - middle.length();
                    middle.append(snippet, 0, Math.min(snippet.length(), remaining));
                }
                position = lineEnd + 1;
            }
        }
        //5. 拼接头部、诊断行与尾部，两段都带省略标记。
        return text.substring(0, head) + middle + "\n[omitted; tail follows]\n" + text.substring(text.length() - tail);
    }
}
