package com.udata.harness.common.support;

import com.udata.harness.repository.ModelContextAgentSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.interceptor.CallChain;
import org.noear.solon.ai.chat.interceptor.ChatInterceptor;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.harness.agent.AgentDefinition;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** 实际 ReAct 请求链；模型由本地拦截器模拟，不访问外部服务。 */
class ModelContextAgentIntegrationTest {
    @TempDir Path tempDir;

    @Test
    void realAgentContinuesFromRollingSummaryAcrossRequestsAndRestart() throws Throwable {
        List<String> requests = new ArrayList<>();
        AtomicInteger summaries = new AtomicInteger();
        ChatModel model = mockModel(requests, new AtomicInteger(), false);
        PersistentContextCompressionInterceptor compression = new PersistentContextCompressionInterceptor(
                10, 0.75, 1, 2, (m, retries, trace, history) ->
                ChatMessage.ofUser("rolling summary " + summaries.incrementAndGet() + ": task constraints"));
        ReActAgent agent = ReActAgent.of(model).name(AgentDefinition.AGENT_MAIN)
                .sessionWindowSize(200).defaultInterceptorAdd(compression).build();
        ModelContextAgentSession session = session("loop");
        for (int i = 0; i < 25; i++) agent.prompt("question " + i).session(session).call();
        assertTrue(summaries.get() >= 2);
        assertTrue(requests.get(1).contains("question 0"));
        assertTrue(requests.get(1).contains("question 1"));
        String lastSummary = "rolling summary " + summaries.get();
        ModelContextAgentSession reopened = session("loop");
        agent.prompt("after restart").session(reopened).call();
        String finalRequest = requests.get(requests.size() - 1);
        assertTrue(finalRequest.contains(lastSummary));
        assertTrue(finalRequest.contains("after restart"));
        assertFalse(finalRequest.contains("question 0\""));
        assertEquals(52, reopened.getMessages().size());
    }

    @Test
    void actualHitlPauseAndResumeKeepsSummaryAndToolPair() throws Throwable {
        List<String> requests = new ArrayList<>();
        AtomicInteger calls = new AtomicInteger();
        AtomicInteger toolExecutions = new AtomicInteger();
        ChatModel model = mockModel(requests, calls, true);
        PersistentContextCompressionInterceptor compression = new PersistentContextCompressionInterceptor(
                10, 0.75, 1, 2, (m, retries, trace, history) -> ChatMessage.ofUser("summary")).contextManagement(1000, 4);
        FunctionTool tool = new FunctionTool() {
            @Override public String name() { return "change"; }
            @Override public String title() { return "change"; }
            @Override public String description() { return "test tool"; }
            @Override public boolean returnDirect() { return false; }
            @Override public java.lang.reflect.Type returnType() { return String.class; }
            @Override public String inputSchema() { return "{\"type\":\"object\",\"properties\":{}}"; }
            @Override public Object handle(java.util.Map<String, Object> args) {
                toolExecutions.incrementAndGet();
                return "changed";
            }
        };
        ReActAgent agent = ReActAgent.of(model).name(AgentDefinition.AGENT_MAIN).sessionWindowSize(200)
                .defaultToolAdd(tool)
                .defaultInterceptorAdd(compression, new HITLInterceptor().onSensitiveTool("change")).build();
        ModelContextAgentSession session = session("approval");
        session.addMessage(ChatMessage.ofUser("old task"));
        session.saveModelContext(Collections.singletonList(ChatMessage.ofUser("previous summary")), true);
        session.taskState().update(Collections.singletonMap("objective", "Change the file safely"));
        agent.prompt("change file").session(session).call();
        // 模拟审批期间重启：从文件恢复 pending、原 Trace 与模型视图。
        session = session("approval");
        assertTrue(session.isPending());
        assertEquals(0, toolExecutions.get());
        assertEquals(1, HITL.getPendingTasks(session).size());
        // 与 ChatService 的清理一致：临时终态不作为模型历史，恢复同一 Trace。
        session.removeLatestMessage(1);
        agent.getTrace(session).getWorkingMemory().removeLastMessage();
        session.saveModelContext(agent.getTrace(session).getWorkingMemory().getMessages(), false);
        HITL.approve(session, HITL.getPendingTask(session));
        session.pending(false, null);
        agent.prompt().session(session).call();
        assertEquals(1, toolExecutions.get());
        assertTrue(requests.get(requests.size() - 1).contains("previous summary"));
        List<ChatMessage> view = session("approval").getLatestMessages(200);
        assertTrue(view.stream().anyMatch(m -> m instanceof ToolMessage));
        assertTrue(view.stream().anyMatch(m -> m instanceof AssistantMessage && m.isToolCalls()));
        assertTrue(view.stream().anyMatch(m -> m.hasMetadata(com.udata.harness.common.context.ContextPlanner.TASK_SNAPSHOT)));
        assertEquals("reply", view.get(view.size() - 1).getContent());
    }

    private ModelContextAgentSession session(String id) {
        return new ModelContextAgentSession(id, tempDir.resolve(id).toString());
    }

    private ChatModel mockModel(List<String> requests, AtomicInteger calls, boolean toolFirst) {
        return ChatModel.of("http://127.0.0.1:1").provider("openai").model("test-model")
                .contextLength(1000000).defaultInterceptorAdd(new ChatInterceptor() {
                    @Override public ChatResponse interceptCall(ChatRequest request, CallChain chain) {
                        requests.add(request.toRequestData());
                        AssistantMessage message = toolFirst && calls.getAndIncrement() == 0
                                ? new AssistantMessage("", null, false, null, null,
                                Collections.singletonList(new ToolCall("0", "call-change", "change", "{}",
                                        Collections.emptyMap())), null)
                                : ChatMessage.ofAssistant("reply");
                        return (ChatResponse) Proxy.newProxyInstance(ChatResponse.class.getClassLoader(),
                                new Class<?>[]{ChatResponse.class}, (proxy, method, args) -> {
                                    switch (method.getName()) {
                                        case "getMessage": return message;
                                        case "getText": case "getContent": return message.getContent();
                                        case "getToolCalls": return message.getToolCalls();
                                        case "getFinishReason": return message.isToolCalls() ? "tool_calls" : "stop";
                                        case "isTerminal": case "hasContent": return true;
                                        case "isEmpty": return false;
                                        default: return null;
                                    }
                                });
                    }
                }).build();
    }
}
