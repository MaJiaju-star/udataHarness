package com.udata.harness.common.support;

import org.noear.solon.ai.agent.react.AbsReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.interceptor.StreamChain;
import reactor.core.publisher.Flux;

/**
 * 监听 ChatModel 工具参数增量，并将其送入当前 ReAct Agent 流。
 */
public class ToolCallStreamInterceptor extends AbsReActInterceptor {
    private ReActTrace trace;

    /** 保存当前推理轨迹，供底层聊天流事件回推 Agent 流。 */
    @Override
    public void onReasonStart(ReActTrace trace, StringBuilder systemPromptBuf) {
        this.trace = trace;
    }

    /**
     * 在不改变聊天流内容的前提下旁路转发工具参数开始、增量和结束事件。
     */
    @Override
    public Flux<ChatEvent> interceptStream(ChatRequest request, StreamChain chain) {
        return chain.doIntercept(request).doOnNext(this::forwardToolEvent);
    }

    /** 将工具参数事件推送到当前 Agent 的流式接收端。 */
    private void forwardToolEvent(ChatEvent event) {
        if (trace == null || !event.is(
                ChatEventType.TOOL_CALL_START,
                ChatEventType.TOOL_CALL_ARGS_DELTA,
                ChatEventType.TOOL_CALL_END)) {
            return;
        }
        trace.pushAgentEvent(new ToolCallStreamEvent(trace, event));
    }
}
