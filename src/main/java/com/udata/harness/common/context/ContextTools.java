package com.udata.harness.common.context;

import org.noear.solon.ai.chat.tool.FunctionTool;
import java.lang.reflect.Type;
import java.util.*;

/**
 * 六个上下文工具的稳定定义（名称、描述、入参 schema）。
 *
 * <p>本类只声明工具，不执行工具：{@link ContextPlanner#handle} 在 {@code onToolCallStart} 中
 * 按名称接管调用，并对当前 trace 的会话执行。因此 {@link FunctionTool#handle}
 * 抛出 {@link IllegalStateException} 作为兜底：若定义被注册到非主会话或其他引擎上，
 * 会立即暴露配置错误，而不是静默返回错误结果。</p>
 *
 * <p>描述文本是模型可见的契约（而非普通注释），已说明“工具结果可能是片段、需恢复”、
 * “检查点不要每轮调用”等使用约束，修改时需同步 {@code docs/context-management.md} 中的工具表。</p>
 *
 * <p>这些定义会被注入到每个主会话的所有请求中，因此它们本身也占用固定提示词体积，
 * 属于上下文管理开销的一部分。</p>
 */
public final class ContextTools {
    /**
     * 工具类，不允许实例化。
     */
    private ContextTools() { }

    /**
     * 返回六个上下文工具的不可变定义列表。
     *
     * <p>顺序即注册顺序：任务状态（读/写）、证据（检索/恢复）、检查点、指标。
     * 返回值为 {@link Collections#unmodifiableList} 包装，避免被调用方意外改写。</p>
     *
     * @return 不可变的工具定义列表
     */
    public static List<FunctionTool> definitions() {
        List<FunctionTool> tools = new ArrayList<>();
        tools.add(tool("context_search", "Search this session's archived tool evidence by exact keywords, file paths, symbols or error codes. All words must match; optional tool filters the source tool.",
                "\"query\":{\"type\":\"string\"},\"tool\":{\"type\":\"string\"},\"limit\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":20}", "\"query\""));
        tools.add(tool("context_restore", "Restore a bounded slice of historical evidence. Verify freshness against current files/tests. Offsets and maxChars are characters; follow nextOffset for more.",
                "\"id\":{\"type\":\"string\"},\"offset\":{\"type\":\"integer\",\"minimum\":0},\"maxChars\":{\"type\":\"integer\",\"minimum\":1,\"maximum\":16000}", "\"id\""));
        tools.add(tool("task_state_get", "Read the durable task objective, acceptance, constraints, phase, module, decisions, completed/pending work, questions, evidence IDs and nextStep.", "", ""));
        tools.add(tool("task_state_update", "Update structured task state using a patch. Establish objective and user constraints early. Objective is immutable; constraints and acceptance accumulate. Other lists replace prior lists. Completed claims are agent-maintained, not independently verified. Evidence must reference existing artifact IDs.",
                "\"patch\":{\"type\":\"object\",\"properties\":{"
                        + "\"objective\":{\"type\":\"string\"},\"phase\":{\"type\":\"string\"},\"module\":{\"type\":\"string\"},\"nextStep\":{\"type\":\"string\"},"
                        + listProperties() + "},\"additionalProperties\":false}", "\"patch\""));
        tools.add(tool("context_checkpoint", "Request a batch summary at a completed phase, closed bug or module switch AFTER updating task state. Requires a task objective. Keeps latest user request and recent tool groups. Do not call every turn: changing history rebuilds message cache prefixes. Failure retains original history.",
                "\"reason\":{\"type\":\"string\"}", "\"reason\""));
        tools.add(tool("context_metrics", "Read context decisions, provider-reported reason token/cache usage and measurement limitations. Structural prefix reuse is not a cache hit measurement.", "", ""));
        return Collections.unmodifiableList(tools);
    }

    /**
     * 拼接所有数组型字段的 schema，与 {@link TaskStateStore} 的列表字段保持一一对应。
     *
     * <p>单独抽出来是为了避免在工具描述中重复书写七个同构字段；由于数组字段集与
     * 与状态存储分离维护，修改任一侧时需同步另一侧，否则会出现“校验认识的字段模型却写不了”的偏差。</p>
     *
     * @return 逗号拼接的 JSON 属性片段，如 {@code "acceptance":{...},"constraints":{...}}
     */
    private static String listProperties() {
        List<String> fields = new ArrayList<>();
        for (String key : Arrays.asList("acceptance", "constraints", "decisions", "completed", "pending", "questions", "evidence")) {
            fields.add("\"" + key + "\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}");
        }
        return String.join(",", fields);
    }

    /**
     * 构造一个返回字符串的 {@link FunctionTool}。
     *
     * <p>{@code properties} 与 {@code required} 直接拼接进 JSON Schema，
     * 因此调用方必须传入合法的 JSON 片段（本类所有调用点均为字面量，不含外部输入）。</p>
     *
     * @param name        工具名，同时也是标题
     * @param description 模型可见的工具描述
     * @param properties  properties 内的 JSON 片段，不含外层括号
     * @param required    required 数组的 JSON 片段，多个用逗号分隔；无必填项时传空串
     * @return 不可执行的工具定义（执行由 {@link ContextPlanner#handle} 接管）
     */
    private static FunctionTool tool(String name, String description, String properties, String required) {
        return new FunctionTool() {
            @Override public String name() { return name; }
            @Override public String title() { return name; }
            @Override public String description() { return description; }
            @Override public boolean returnDirect() { return false; }
            @Override public Type returnType() { return String.class; }
            @Override public String inputSchema() { return "{\"type\":\"object\",\"properties\":{" + properties + "},\"required\":[" + required + "],\"additionalProperties\":false}"; }
            @Override public Object handle(Map<String, Object> args) { throw new IllegalStateException("Context tools require the main session context interceptor"); }
        };
    }
}
