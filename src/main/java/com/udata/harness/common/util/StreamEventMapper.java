package com.udata.harness.common.util;

import org.noear.snack4.ONode;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.agent.AgentChunk;
import org.noear.solon.ai.agent.trace.Metrics;
import org.noear.solon.ai.agent.react.ReActChunk;
import org.noear.solon.ai.agent.react.RunEndChunk;
import org.noear.solon.ai.agent.react.RunStartChunk;
import org.noear.solon.ai.agent.react.task.ActionChunk;
import org.noear.solon.ai.agent.react.task.ObservationChunk;
import org.noear.solon.ai.agent.react.task.ReasonChunk;
import org.noear.solon.ai.agent.react.intercept.HITLTask;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 Solon AI AgentChunk 转换成前端稳定的 JSON 事件协议。
 *
 * <p>映射层隔离框架类型和 UI 协议；未知 Chunk 降级为通用 chunk 事件，避免框架
 * 增加事件类型时导致前端解析失败。</p>
 */
public final class StreamEventMapper {
    private StreamEventMapper() {
    }

    public static String session(String sessionId) {
        Map<String, Object> event = base("session", null);
        event.put("sessionId", sessionId);
        return ONode.serialize(event);
    }

    public static String error(String message) {
        Map<String, Object> event = base("error", null);
        event.put("message", message);
        return ONode.serialize(event);
    }

    public static String hitl(HITLTask task) {
        return hitl(List.of(task));
    }

    /**
     * 将同一轮批量挂起的工具调用合并成一张审批事件。
     *
     * <p>顶层保留第一项的旧字段以兼容已有前端；新前端应读取 tasks，并将所有
     * callUuid 一并提交，保证 Harness 恢复前整批调用都已有明确决策。</p>
     */
    public static String hitl(List<HITLTask> tasks) {
        Map<String, Object> event = base("hitl", null);
        List<Map<String, Object>> items = new ArrayList<>();
        if (tasks != null) {
            for (HITLTask task : tasks) {
                if (task != null) {
                    items.add(hitlTask(task));
                }
            }
        }
        if (!items.isEmpty()) {
            Map<String, Object> first = items.get(0);
            event.put("callUuid", first.get("callUuid"));
            event.put("toolName", first.get("toolName"));
            event.put("args", first.get("args"));
            event.put("comment", first.get("comment"));
        }
        event.put("tasks", items);
        event.put("count", items.size());
        return ONode.serialize(event);
    }

    private static Map<String, Object> hitlTask(HITLTask task) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("callUuid", task.getCallUuid());
        item.put("toolName", task.getToolName());
        item.put("args", task.getArgs());
        item.put("comment", task.getComment());
        return item;
    }

    /**
     * 判断 Chunk 是否是 HITL 恢复时对挂起前 Reason 的重放。
     *
     * <p>Solon Flow 从 Action 节点恢复时可能再次发射上一 Reason 的文本 Chunk；
     * {@code reasonId} 保持不变。只过滤 ReasonChunk，不影响同一次恢复中新生成的
     * Action、Observation 和具有新 reasonId 的模型文本。</p>
     */
    public static boolean isReasonReplay(AgentChunk chunk, String suspendedReasonId) {
        return suspendedReasonId != null
                && chunk instanceof ReasonChunk reason
                && suspendedReasonId.equals(reason.getReasonId());
    }

    public static String map(AgentChunk chunk) {
        String type = chunk.getClass().getSimpleName();
        Map<String, Object> event = base("chunk", chunk);

        if (chunk instanceof RunStartChunk) {
            event.put("type", "run_start");
        } else if (chunk instanceof RunEndChunk runEnd) {
            event.put("type", "run_end");
            putRunMetrics(event, runEnd.getMetrics());
        } else if (chunk instanceof ActionChunk action) {
            event.put("type", "tool_start");
            event.put("callId", action.getCallId());
            event.put("toolName", action.getToolName());
            event.put("args", action.getArgs());
            if (action.hasMeta("fileOperation")) {
                event.put("fileOperation", action.getMeta().get("fileOperation"));
            }
        } else if (chunk instanceof ObservationChunk observation) {
            event.put("type", "tool_end");
            event.put("callId", observation.getCallId());
            event.put("toolName", observation.getToolName());
            event.put("args", observation.getArgs());
            event.put("durationMs", observation.getDurationMs());
            if (observation.getError() != null) {
                event.put("error", observation.getError().getMessage());
            }
        } else if (chunk instanceof ReasonChunk reason) {
            // HITL 挂起时 Solon ReAct 会额外发出 response == null 的 ReasonChunk，
            // 其内容是 pendingReason（例如“高危操作，需要人工介入确认”），并非模型正文。
            // 该信息随后会通过结构化 hitl 事件发送，不能再次作为 text 追加到聊天内容。
            //
            // 模型准备调用工具时还会发出带 ToolCalls 的最终 ReasonChunk。部分模型适配器
            // 会在该 Chunk 的 content 中附带本轮已经逐段发送过的累计正文；如果继续映射
            // 为 text，多工具链每进入一次 Action 都会把先前正文再追加一遍。
            // 部分模型会把后续一轮的 thinking 与 toolCalls 放在同一个终态消息中。
            // thinking 必须优先透传；非 thinking 的 toolCalls 正文标记为 replay 候选，
            // 由前端按累计文本规则合并，既保留新内容又不重复追加旧内容。
            event.put("type", reason.isError()
                    ? "reason_interrupted"
                    : reason.isThinking()
                    ? "thinking"
                    : reason.isToolCalls() ? "text_replay" : "text");
            event.put("reasonId", reason.getReasonId());
            event.put("finished", reason.isFinished());
            event.put("toolCalls", reason.isToolCalls());
            if (reason.getResponse() != null && reason.isFinished()) {
                putUsage(event, reason.getResponse().getUsage(), "model_call");
            }
        } else if (chunk instanceof ReActChunk react) {
            // HITL 会暂时把 Trace 标记为 abnormal，并用 pendingReason 作为本段
            // finalAnswer；这不是运行错误，真正的交互状态由随后 hitl 事件承载。
            // 若映射为 error，前端会出现一个批准后仍残留的红色错误框。
            event.put("type", react.getResponse().getSession().isPending()
                    ? "run_pending"
                    : react.isAbnormal() ? "error" : "done");
        } else {
            event.put("chunkType", type);
        }

        if (chunk.hasContent()
                && !(chunk instanceof ReasonChunk reason && reason.isError())
                && !(chunk instanceof ReActChunk react
                && react.getResponse().getSession().isPending())) {
            event.put("content", chunk.getContent());
        }
        return ONode.serialize(event);
    }

    /** 将一次模型调用返回的真实 Token 用量写入 SSE 事件。 */
    private static void putUsage(Map<String, Object> event, AiUsage usage, String scope) {
        if (usage == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("promptTokens", usage.promptTokens());
        data.put("thinkTokens", usage.thinkTokens());
        data.put("completionTokens", usage.completionTokens());
        data.put("totalTokens", usage.totalTokens());
        data.put("cacheCreationInputTokens", usage.cacheCreationInputTokens());
        data.put("cacheReadInputTokens", usage.cacheReadInputTokens());
        event.put("usageScope", scope);
        event.put("usage", data);
    }

    /** 将智能体整轮汇总指标写入结束事件，并计算包含工具耗时的平均输出速度。 */
    private static void putRunMetrics(Map<String, Object> event, Metrics metrics) {
        if (metrics == null) {
            return;
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("promptTokens", metrics.getPromptTokens());
        data.put("completionTokens", metrics.getCompletionTokens());
        data.put("totalTokens", metrics.getTotalTokens());
        event.put("usageScope", "run");
        event.put("usage", data);
        event.put("durationMs", metrics.getTotalDuration());
        if (metrics.getTotalDuration() > 0L) {
            double tokensPerSecond = metrics.getCompletionTokens() * 1000D / metrics.getTotalDuration();
            event.put("tokensPerSecond", Math.round(tokensPerSecond * 10D) / 10D);
        }
    }

    private static Map<String, Object> base(String type, AgentChunk chunk) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("timestamp", System.currentTimeMillis());
        if (chunk != null) {
            event.put("runId", chunk.getRunId());
            event.put("agentName", chunk.getAgentName());
        }
        return event;
    }
}
