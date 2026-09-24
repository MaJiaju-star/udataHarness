package com.udata.harness.common.support;

import org.noear.solon.Utils;
import org.noear.solon.ai.agent.AbsAgentEvent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.tool.ToolCall;

/**
 * 将模型层工具调用参数流桥接到 Agent 事件流。
 */
public class ToolCallStreamEvent extends AbsAgentEvent {
    private final ChatEventType eventType;
    private final String streamId;
    private final String callId;
    private final String toolName;
    private final String text;

    /**
     * 根据底层聊天事件创建稳定的工具参数流事件。
     */
    public ToolCallStreamEvent(ReActTrace trace, ChatEvent event) {
        super(trace.getRunId(), trace.getAgentName(), trace.getSession());
        ToolCall toolCall = event.getToolCall();
        this.eventType = event.getType();
        this.callId = event.getToolCallId();
        this.toolName = toolCall == null ? null : toolCall.getName();
        this.text = event.getTextOrEmpty();
        this.streamId = buildStreamId(event, toolCall);
    }

    /** 返回底层工具调用事件类型。 */
    public ChatEventType getEventType() {
        return eventType;
    }

    /** 返回一次流式工具调用在前端使用的稳定标识。 */
    public String getStreamId() {
        return streamId;
    }

    /** 返回模型提供商的工具调用标识。 */
    public String getCallId() {
        return callId;
    }

    /** 返回工具名称；部分参数分片可能不携带名称。 */
    public String getToolName() {
        return toolName;
    }

    /** 返回参数增量或结束事件携带的完整参数文本。 */
    @Override
    public String getText() {
        return text;
    }

    /**
     * 使用响应、步骤和工具索引构造稳定标识，兼容后续分片不带 callId 的模型协议。
     */
    private String buildStreamId(ChatEvent event, ToolCall toolCall) {
        String index = toolCall == null ? null : toolCall.getIndex();
        if (Utils.isBlank(index)) {
            index = event.getIndex() >= 0 ? String.valueOf(event.getIndex()) : event.getToolCallId();
        }
        if (Utils.isBlank(index)) {
            index = "tool";
        }
        return String.valueOf(event.getResponseId()) + ":" + event.getStep() + ":" + index;
    }
}
