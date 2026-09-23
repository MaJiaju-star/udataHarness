package com.udata.harness.common.util;

import com.udata.harness.common.support.ToolCallStreamEvent;
import org.junit.jupiter.api.Test;
import org.noear.snack4.ONode;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.agent.react.ReActResponse;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.RunEndEvent;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.react.task.ReasonEndEvent;
import org.noear.solon.ai.agent.react.task.ReasonStartEvent;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventDefault;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.ToolCall;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证后端与 React 前端之间的 HITL SSE 事件契约。
 */
class StreamEventMapperTest {
    @Test
    void identifiesOnlyTheSuspendedReasonAsReplay() {
        ReActTrace trace = new ReActTrace();
        String reasonId = trace.getCurrentReasonId();
        ReasonStartEvent originalReason = new ReasonStartEvent(trace, "system");

        assertTrue(StreamEventMapper.isReasonReplay(originalReason, reasonId));
        assertFalse(StreamEventMapper.isReasonReplay(originalReason, "another-reason"));
        assertFalse(StreamEventMapper.isReasonReplay(originalReason, null));
    }

    @Test
    void mapsReasonStartWithoutAssistantText() {
        ReasonStartEvent reasonStart = new ReasonStartEvent(new ReActTrace(), "system");

        ONode event = ONode.ofJson(StreamEventMapper.map(reasonStart));

        assertEquals("reason_start", event.get("type").getString());
        assertFalse(event.hasKey("content"));
    }

    @Test
    void marksAccumulatedTextFromToolCallReasonAsReplayCandidate() {
        AssistantMessage toolCallMessage = (AssistantMessage) ChatMessage.fromJson("""
                {
                  "role": "assistant",
                  "content": "好的，我先读取文件并分析数据。",
                  "toolCalls": [{
                    "id": "call-1",
                    "name": "read",
                    "arguments": {"file_path": "data.csv"}
                  }]
                }
                """);
        ReasonEndEvent toolCallReason = new ReasonEndEvent(
                new ReActTrace(), streamingResponse(), toolCallMessage, 10L);

        ONode event = ONode.ofJson(StreamEventMapper.map(toolCallReason));

        assertTrue(toolCallReason.isToolCalls());
        assertEquals("text_replay", event.get("type").getString());
        assertEquals("好的，我先读取文件并分析数据。", event.get("content").getString());
    }

    @Test
    void preservesThinkingWhenToolCallFinishesInTheSameChunk() {
        AssistantMessage toolCallMessage = (AssistantMessage) ChatMessage.fromJson("""
                {
                  "role": "assistant",
                  "toolCalls": [{
                    "id": "call-2",
                    "name": "bash",
                    "arguments": {"command": "python analyze.py"}
                  }]
                }
                """);
        AssistantMessage thinkingWithToolCall = new AssistantMessage(
                "",
                "我需要根据第一次工具结果继续分析。",
                true,
                null,
                null,
                toolCallMessage.getToolCalls(),
                null);
        ReasonEndEvent reason = new ReasonEndEvent(
                new ReActTrace(), streamingResponse(), thinkingWithToolCall, 10L);

        ONode event = ONode.ofJson(StreamEventMapper.map(reason));

        assertTrue(reason.isToolCalls());
        assertEquals("thinking", event.get("type").getString());
        assertEquals("我需要根据第一次工具结果继续分析。", event.get("content").getString());
    }

    @Test
    void mapsHitlSuspensionAsPendingInsteadOfError() {
        AgentSession session = InMemoryAgentSession.of("pending-session");
        session.pending(true, "高危操作，需要人工介入确认。");
        ReActTrace trace = new ReActTrace();
        trace.setFinalAnswer("高危操作，需要人工介入确认。");
        ReActResponse response = new ReActResponse(
                session,
                trace,
                ChatMessage.ofAssistant("高危操作，需要人工介入确认。"));

        ONode event = ONode.ofJson(StreamEventMapper.map(new RunEndEvent(response)));

        assertEquals("run_pending", event.get("type").getString());
        assertFalse(event.hasKey("content"));
    }

    @Test
    void mapsEveryTaskInBatchWithStableCallUuid() {
        HITLTask first = new HITLTask(
                "call-1", "bash", Map.of("command", "node --version"), "需要确认");
        HITLTask second = new HITLTask(
                "call-2", "bash", Map.of("command", "python --version"), "需要确认");

        ONode event = ONode.ofJson(StreamEventMapper.hitl(List.of(first, second)));

        assertEquals("hitl", event.get("type").getString());
        assertEquals(2, event.get("count").getInt());
        assertEquals("call-1", event.get("callUuid").getString());
        assertEquals("call-1", event.get("tasks").get(0).get("callUuid").getString());
        assertEquals("call-2", event.get("tasks").get(1).get("callUuid").getString());
        assertEquals("bash", event.get("tasks").get(1).get("toolName").getString());
    }

    @Test
    void mapsToolArgumentDeltasWithStableStreamId() {
        ReActTrace trace = new ReActTrace();
        ToolCall shard = new ToolCall("0", "provider-call", "read", "{\"file_", null);
        ChatEvent chatEvent = ChatEventDefault.of(ChatEventType.TOOL_CALL_ARGS_DELTA)
                .responseId("response-1")
                .step(0)
                .toolCallId("provider-call")
                .toolCall(shard)
                .text("{\"file_")
                .build();

        ONode event = ONode.ofJson(StreamEventMapper.map(new ToolCallStreamEvent(trace, chatEvent)));

        assertEquals("tool_args_delta", event.get("type").getString());
        assertEquals("response-1:0:0", event.get("streamId").getString());
        assertEquals("read", event.get("toolName").getString());
        assertEquals("{\"file_", event.get("content").getString());
    }

    @Test
    void mapsModelUsageAndRunThroughput() {
        AiUsage usage = new AiUsage(100, 20, 40, 160, 8, 60, null);
        ChatResponse response = (ChatResponse) Proxy.newProxyInstance(
                ChatResponse.class.getClassLoader(),
                new Class<?>[]{ChatResponse.class},
                (proxy, method, args) -> {
                    if ("getUsage".equals(method.getName())) return usage;
                    if ("isFinished".equals(method.getName())) return true;
                    return method.getReturnType() == boolean.class ? false : null;
                });
        ReasonEndEvent reason = new ReasonEndEvent(
                new ReActTrace(), response, ChatMessage.ofAssistant("完成"), 10L);
        ONode reasonEvent = ONode.ofJson(StreamEventMapper.map(reason));

        assertEquals(100, reasonEvent.get("usage").get("promptTokens").getInt());
        assertEquals(60, reasonEvent.get("usage").get("cacheReadInputTokens").getInt());

        AgentSession session = InMemoryAgentSession.of("metrics-session");
        ReActResponse runResponse = new ReActResponse(
                session, new ReActTrace(), ChatMessage.ofAssistant("完成"));
        runResponse.getMetrics().setPromptTokens(100);
        runResponse.getMetrics().setCompletionTokens(40);
        runResponse.getMetrics().setTotalTokens(140);
        runResponse.getMetrics().setCacheCreationInputTokens(5);
        runResponse.getMetrics().setCacheReadInputTokens(60);
        runResponse.getMetrics().setTotalDuration(2000);
        ONode runEvent = ONode.ofJson(StreamEventMapper.map(new RunEndEvent(runResponse)));

        assertEquals(2000, runEvent.get("durationMs").getInt());
        assertEquals(20D, runEvent.get("tokensPerSecond").getDouble());
        assertEquals(5, runEvent.get("usage").get("cacheCreationInputTokens").getInt());
        assertEquals(60, runEvent.get("usage").get("cacheReadInputTokens").getInt());
    }

    private ChatResponse streamingResponse() {
        return (ChatResponse) Proxy.newProxyInstance(
                ChatResponse.class.getClassLoader(),
                new Class<?>[]{ChatResponse.class},
                (proxy, method, args) -> method.getReturnType() == boolean.class ? false : null);
    }
}
