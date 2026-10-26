package com.udata.harness.common.support;

import org.noear.solon.ai.agent.Agent;
import org.noear.solon.ai.agent.react.AbsReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.agent.react.task.ReasonDeltaEvent;
import org.noear.solon.ai.chat.event.ChatEventDefault;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.interceptor.CallChain;
import org.noear.solon.ai.chat.interceptor.StreamChain;
import reactor.core.publisher.Flux;

import java.util.Collection;

/** Request-local boundary: apply the original skip decisions, then allow explanation only. */
public final class ExplainOnlyAfterSkipInterceptor extends AbsReActInterceptor {
    private boolean explaining;
    private final String skippedMessage;

    public ExplainOnlyAfterSkipInterceptor(String skippedMessage) { this.skippedMessage = skippedMessage; }

    @Override
    public void onReasonStart(ReActTrace trace, StringBuilder systemPrompt) {
        explaining = true;
        systemPrompt.append("\n用户已跳过待审批操作。本段只说明原计划、用途和跳过的影响，不得调用任何工具。\n");
    }

    @Override public ChatResponse interceptCall(ChatRequest request, CallChain chain) throws java.io.IOException {
        request.getOptions().tool_choice("none");
        return chain.doIntercept(request);
    }

    @Override public Flux<ChatEvent> interceptStream(ChatRequest request, StreamChain chain) {
        request.getOptions().tool_choice("none");
        return chain.doIntercept(request);
    }

    @Override public void onAgentEnd(ReActTrace trace) { setEnabled(false); }

    @Override
    public void onActionStart(ReActTrace trace, Collection<ToolExchanger> calls) {
        if (calls == null || calls.isEmpty()) return;
        if (!explaining) {
            // Resume the old batch as observations only, including calls that did not
            // themselves require approval. Nothing in this cancelled action may run.
            for (ToolExchanger call : calls) call.setResult(skippedMessage);
            return;
        }
        // Runs after HITL's preflight: even a model/provider ignoring tool_choice must
        // not create another approval loop or execute a different equivalent tool.
        HITL.clear(trace.getSession());
        trace.getSession().pending(false, null);
        String message = "已跳过所选操作，工具未执行。模型仍尝试调用工具，已阻止继续执行。"
                + "如需执行，请重新发送请求并明确授权。";
        trace.setFinalAnswer(message, false);
        if (trace.hasStreamSink()) {
            trace.pushAgentEvent(new ReasonDeltaEvent(trace, ChatEventDefault.of(ChatEventType.TEXT_DELTA).text(message).build()));
            trace.pushAgentEvent(new ReasonDeltaEvent(trace, ChatEventDefault.of(ChatEventType.TEXT_END).build()));
        }
        trace.setLastReasonMessage(null);
        trace.setRoute(Agent.ID_END);
    }
}
