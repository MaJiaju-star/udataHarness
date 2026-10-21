package com.udata.harness.notification;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 一次会话执行的通知变量状态机。
 *
 * <p>一个用户回合（execution）可能跨越多个 HITL 分段（segment）：
 * {@link #begin} 在会话开始时建立状态，{@link #resume} 在每个分段开始时
 * 重置计时起点，{@link #observe} 逐条消费流式事件累积文本与错误，
 * {@link #end} 在回合真正结束时生成模板变量。</p>
 *
 * <p>子任务事件（含 {@code parentRunId}）、{@code subagent_event} 与压缩事件
 * 不参与累积；正常的分段结束（回合未完成、无错误、未停止）只累计耗时，
 * {@link #end} 返回 {@code null}，等待下一个分段。</p>
 */
public final class NotificationExecution {

    /** 会话上下文中保存执行状态的键。 */
    public static final String KEY = "udata.notification.execution";

    private NotificationExecution() { }

    /**
     * 开始一次新的执行，初始化全部状态字段。
     *
     * @param id 执行 id（计划任务使用其自身 id，聊天使用随机 UUID）
     * @param source 执行来源：{@code chat} 或 {@code scheduled}
     * @param workspaceId 工作区 id
     * @param workspaceName 工作区名称
     * @param sessionId 会话 id
     * @param title 会话标题
     * @param now 当前毫秒时间戳
     * @return 新的执行状态
     */
    public static Map<String, Object> begin(String id, String source, String workspaceId, String workspaceName,
                                            String sessionId, String title, long now) {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("execution.id", id);
        state.put("execution.source", source);
        state.put("workspace.id", workspaceId);
        state.put("workspace.name", workspaceName);
        state.put("session.id", sessionId);
        state.put("session.title", title);
        state.put("startedAt", Instant.ofEpochMilli(now).toString());
        state.put("segmentStarted", now);
        state.put("elapsed", 0L);
        state.put("text", "");
        state.put("error", "");
        return state;
    }

    /**
     * 新分段开始：重置计时起点；已结束的执行不受影响。
     *
     * @param state 执行状态
     * @param now 当前毫秒时间戳
     */
    public static void resume(Map<String, Object> state, long now) {
        if (!Boolean.TRUE.equals(state.get("finished"))) {
            state.put("segmentStarted", now);
        }
    }

    /**
     * 消费一条流式事件，更新累积文本、错误与终态标记。
     *
     * <p>{@code text} 事件累积文本（上限 6000 字符），{@code text_replay}
     * 整体替换文本，{@code run_end} 标记回合正常到达终态，
     * {@code error} 记录错误信息（上限 600 字符）；已结束的执行与
     * 子任务/压缩相关事件直接忽略。</p>
     *
     * @param state 执行状态
     * @param event 流式事件
     */
    public static void observe(Map<String, Object> state, Map<String, Object> event) {
        // 1. 已结束的执行与子任务、压缩事件直接忽略
        if (Boolean.TRUE.equals(state.get("finished")) || event.get("parentRunId") != null
                || "subagent_event".equals(event.get("type")) || "compaction".equals(event.get("usageScope"))) return;
        // 2. 按事件类型更新累积状态
        String type = String.valueOf(event.get("type"));
        if ("reason_start".equals(type)) state.put("text", ""); // 新一轮回答开始，清空累积文本
        if ("text".equals(type) && !Boolean.TRUE.equals(event.get("finished"))) { // 追加增量文本
            state.put("text", limit(text(state, "text") + text(event, "content"), 6000));
        }
        if ("text_replay".equals(type) && !text(event, "content").isEmpty()) { // 整体替换文本
            state.put("text", limit(text(event, "content"), 6000));
        }
        if ("run_end".equals(type)) state.put("terminal", true); // 标记回合到达正常终态
        if ("error".equals(type)) {
            state.put("error", limit(text(event, "message").isEmpty() ? text(event, "content") : text(event, "message"), 600));
        }
    }

    /**
     * 结束执行并生成模板变量。
     *
     * <p>状态判定：被停止或 {@code CANCEL} 信号记为 {@code cancelled}；
     * 存在错误、{@code ON_ERROR} 信号或未见到 {@code run_end} 记为 {@code failed}；
     * 其余记为 {@code succeeded}。结果摘要会剥离思考片段并截断到 1200 字符，
     * 失败但无具体错误时补一句通用说明。</p>
     *
     * @param state 执行状态
     * @param signal 结束信号
     * @param stopped 是否被用户主动停止
     * @param pending 是否只是分段结束、回合仍在继续
     * @param now 当前毫秒时间戳
     * @return 模板变量；已结束或回合未真正完成时返回 {@code null}
     */
    public static Map<String, Object> end(Map<String, Object> state, String signal, boolean stopped, boolean pending, long now) {
        // 1. 已结束的执行直接返回，避免重复生成变量
        if (Boolean.TRUE.equals(state.get("finished"))) return null;
        // 2. 累计本分段耗时并重置计时起点
        long elapsed = number(state.get("elapsed")) + Math.max(0, now - number(state.get("segmentStarted")));
        state.put("elapsed", elapsed);
        state.put("segmentStarted", now);
        // 3. 仅分段结束且无任何异常时，等待下一个分段
        if (pending && text(state, "error").isEmpty() && !stopped && !"CANCEL".equals(signal) && !"ON_ERROR".equals(signal)) return null;
        // 4. 判定终态：停止/CANCEL 记取消；错误/ON_ERROR/未见 run_end 记失败；其余记成功
        String status = stopped || "CANCEL".equals(signal) ? "cancelled"
            : !text(state, "error").isEmpty() || "ON_ERROR".equals(signal) || !Boolean.TRUE.equals(state.get("terminal")) ? "failed" : "succeeded";
        // 5. 以固定变量词表复制基础变量
        Map<String, Object> result = new LinkedHashMap<>();
        for (String key : NotificationTemplate.VARIABLES) result.put(key, state.getOrDefault(key, ""));
        // 6. 补充执行结果：状态、耗时、结束时间、摘要与错误
        result.put("execution.status", status);
        result.put("execution.durationMs", elapsed);
        result.put("execution.duration", elapsed < 60000
            ? String.format(Locale.ROOT, "%.1f秒", elapsed / 1000.0)
            : elapsed / 60000 + "分" + elapsed / 1000 % 60 + "秒");
        result.put("finishedAt", Instant.ofEpochMilli(now).toString());
        result.put("result.summary", limit(text(state, "text").replaceAll("(?is)<think(?:ing)?>.*?(?:</think(?:ing)?>|$)", "").trim(), 1200));
        result.put("result.error", text(state, "error").isEmpty() && "failed".equals(status) ? "对话执行未正常完成" : text(state, "error"));
        return result;
    }

    /**
     * 安全取字符串：缺失或为 null 时返回空串。
     *
     * @param map 数据源
     * @param key 字段名
     * @return 字段文本
     */
    public static String text(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    /** 安全取数值：非 {@link Number} 时按 0 处理。 */
    private static long number(Object value) {
        return value instanceof Number ? ((Number) value).longValue() : 0;
    }

    /**
     * 截断字符串到指定长度，超出部分以省略号代替。
     *
     * @param value 原始字符串
     * @param size 最大长度
     * @return 截断后的字符串
     */
    public static String limit(String value, int size) {
        return value.length() > size ? value.substring(0, size) + "…" : value;
    }
}
