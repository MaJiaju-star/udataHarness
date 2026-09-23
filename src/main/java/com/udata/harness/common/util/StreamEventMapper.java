package com.udata.harness.common.util;

import com.udata.harness.common.support.ToolCallStreamEvent;
import org.noear.snack4.ONode;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.trace.Metrics;
import org.noear.solon.ai.agent.react.RunEndEvent;
import org.noear.solon.ai.agent.react.RunStartEvent;
import org.noear.solon.ai.agent.react.task.ReasonDeltaEvent;
import org.noear.solon.ai.agent.react.task.ReasonEndEvent;
import org.noear.solon.ai.agent.react.task.ReasonStartEvent;
import org.noear.solon.ai.agent.react.task.ToolCallEndEvent;
import org.noear.solon.ai.agent.react.task.ToolCallStartEvent;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.chat.event.ChatEventType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 将 Solon AI AgentEvent 转换成前端稳定的 JSON 事件协议。
 *
 * <p>映射层隔离框架类型和 UI 协议；未知 Event 降级为通用 chunk 事件，避免框架
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
     * 判断 Event 是否是 HITL 恢复时对挂起前 Reason 的重放。
     *
     * <p>Solon Flow 从 ToolCall 节点恢复时可能再次发射上一 Reason 的事件；
     * {@code reasonId} 保持不变。只过滤 Reason 事件，不影响同一次恢复中新生成的
     * ToolCall 事件和具有新 reasonId 的模型文本。</p>
     */
    public static boolean isReasonReplay(AgentEvent event, String suspendedReasonId) {
        if (suspendedReasonId == null) {
            return false;
        }
        if (event instanceof ReasonStartEvent reason) {
            return suspendedReasonId.equals(reason.getReasonId());
        }
        if (event instanceof ReasonDeltaEvent reason) {
            return suspendedReasonId.equals(reason.getReasonId());
        }
        return event instanceof ReasonEndEvent reason
                && suspendedReasonId.equals(reason.getReasonId());
    }

    public static String map(AgentEvent agentEvent) {
        String type = agentEvent.getClass().getSimpleName();
        Map<String, Object> event = base("chunk", agentEvent);

        if (agentEvent instanceof RunStartEvent) {
            event.put("type", "run_start");
        } else if (agentEvent instanceof RunEndEvent runEnd) {
            if (runEnd.getResponse().getSession().isPending()) {
                event.put("type", "run_pending");
            } else if (runEnd.isAbnormal()) {
                event.put("type", "error");
                event.put("message", runEnd.getText());
            } else {
                event.put("type", "run_end");
            }
            putRunMetrics(event, runEnd.getMetrics());
        } else if (agentEvent instanceof ToolCallStreamEvent toolStream) {
            mapToolStreamEvent(event, toolStream);
        } else if (agentEvent instanceof ToolCallStartEvent action) {
            event.put("type", "tool_start");
            event.put("callId", action.getCallId());
            event.put("toolName", action.getToolName());
            event.put("args", action.getArgs());
            if (action.hasMeta("fileOperation")) {
                event.put("fileOperation", action.getMeta().get("fileOperation"));
            }
        } else if (agentEvent instanceof ToolCallEndEvent observation) {
            event.put("type", "tool_end");
            event.put("callId", observation.getCallId());
            event.put("toolName", observation.getToolName());
            event.put("args", observation.getArgs());
            event.put("durationMs", observation.getDurationMs());
            if (observation.getError() != null) {
                event.put("error", observation.getError().getMessage());
            }
        } else if (agentEvent instanceof ReasonStartEvent reason) {
            event.put("type", "reason_start");
            event.put("reasonId", reason.getReasonId());
        } else if (agentEvent instanceof ReasonDeltaEvent reason) {
            event.put("type", reason.isThinking() ? "thinking" : "text");
            event.put("reasonId", reason.getReasonId());
            event.put("finished", false);
        } else if (agentEvent instanceof ReasonEndEvent reason) {
            boolean hasThinking = reason.getThinking() != null && !reason.getThinking().isBlank();
            event.put("type", hasThinking ? "thinking" : "text_replay");
            event.put("reasonId", reason.getReasonId());
            event.put("finished", true);
            event.put("toolCalls", reason.isToolCalls());
            if (hasThinking) {
                event.put("content", reason.getThinking());
            }
            if (reason.getResponse() != null) {
                putUsage(event, reason.getResponse().getUsage(), "model_call");
            }
        } else {
            event.put("chunkType", type);
        }

        if (!event.containsKey("content")
                && agentEvent.hasText()
                && !(agentEvent instanceof RunEndEvent)) {
            event.put("content", agentEvent.getText());
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

    /** 将模型工具参数流映射为前端可增量消费的事件。 */
    private static void mapToolStreamEvent(Map<String, Object> event, ToolCallStreamEvent toolStream) {
        if (toolStream.getEventType() == ChatEventType.TOOL_CALL_START) {
            event.put("type", "tool_args_start");
        } else if (toolStream.getEventType() == ChatEventType.TOOL_CALL_ARGS_DELTA) {
            event.put("type", "tool_args_delta");
        } else {
            event.put("type", "tool_args_end");
        }
        event.put("streamId", toolStream.getStreamId());
        event.put("callId", toolStream.getCallId());
        event.put("toolName", toolStream.getToolName());
    }

    private static Map<String, Object> base(String type, AgentEvent agentEvent) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", type);
        event.put("timestamp", System.currentTimeMillis());
        if (agentEvent != null) {
            event.put("runId", agentEvent.getRunId());
            event.put("agentName", agentEvent.getAgentName());
        }
        return event;
    }
}
