package com.udata.harness.service.impl;

import com.sun.net.httpserver.HttpServer;
import com.udata.harness.common.domain.SessionMetadata;
import com.udata.harness.common.domain.WorkspaceMetadata;
import com.udata.harness.common.request.HitlDecisionRequest;
import com.udata.harness.common.support.ActiveRunRegistry;
import com.udata.harness.common.support.ScheduledRunEvents;
import com.udata.harness.repository.SessionRepository;
import com.udata.harness.service.UserHarnessEngineService;
import com.udata.harness.service.UserWorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLInterceptor;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.tool.AbsToolProvider;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.annotation.Param;
import org.noear.solon.ai.annotation.ToolMapping;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** Loopback model and real Harness/HITL; no external API or real mutation. */
class HitlDecisionIntegrationTest {
    @TempDir Path directory;

    @Test void rejectingOneToolStopsWithoutAnotherModelRequest() throws Throwable { reject(1); }
    @Test void rejectingBatchStopsWithoutExecutingAnyToolAndPersistsCleanState() throws Throwable { reject(2); }

    private void reject(int batch) throws Throwable {
        try (Fixture f = new Fixture(batch)) {
            SessionRepository repository = new SessionRepository(directory.resolve("sessions"));
            SessionMetadata metadata = repository.create("alice", "HITL test", "stub");
            AgentSession session = repository.getSession(metadata.getSessionId());
            f.engine.prompt("prepare operations").session(session).call();
            assertEquals(batch, HITL.getPendingTasks(session).size());
            assertEquals(0, f.tool.executions.get());

            ChatServiceImpl service = new ChatServiceImpl();
            ActiveRunRegistry active = new ActiveRunRegistry();
            ScheduledRunEvents scheduled = new ScheduledRunEvents();
            List<ONode> events = new ArrayList<>();
            scheduled.listen((id, event) -> events.add(event));
            session.getContext().put(ScheduledRunEvents.SESSION_KEY, "scheduled-test");
            WorkspaceMetadata workspace = new WorkspaceMetadata(); workspace.setPath(directory.toString());
            inject(service, "engines", Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{UserHarnessEngineService.class}, (p, method, args) -> f.engine));
            inject(service, "workspaces", Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{UserWorkspaceService.class}, (p, method, args) -> workspace));
            inject(service, "sessionRepository", repository);
            inject(service, "activeRuns", active);
            inject(service, "scheduledRunEvents", scheduled);
            HitlDecisionRequest request = new HitlDecisionRequest();
            request.setSessionId(session.getSessionId()); request.setAction("reject"); request.setAlwaysAllow(true);
            List<String> output = service.decide("alice", request).collectList().block();
            assertTrue(output.stream().anyMatch(s -> s.contains(ChatServiceImpl.REJECTED_TURN_MESSAGE)));
            assertTrue(output.stream().anyMatch(s -> s.contains("hitl_rejected")));
            assertFalse(output.stream().anyMatch(s -> "error".equals(ONode.ofJson(s).get("type").getString())), output.toString());
            assertFalse(output.stream().anyMatch(s -> "tool_start".equals(ONode.ofJson(s).get("type").getString())));
            assertEquals(1, f.requests.size(), "Rejection must not resume the model");
            assertEquals(0, f.tool.executions.get());
            assertFalse(session.isPending()); assertTrue(HITL.getPendingTasks(session).isEmpty());
            assertFalse(active.isActive(session.getSessionId()));
            assertTrue(events.stream().anyMatch(e -> "CANCEL".equals(e.get("signal").getString())));
            assertNull(session.getContext().get(ChatServiceImpl.ALWAYS_ALLOWED_TOOLS_KEY));
            AgentSession restored = new SessionRepository(directory.resolve("sessions")).getSession(session.getSessionId());
            assertFalse(restored.isPending()); assertTrue(HITL.getPendingTasks(restored).isEmpty());
            assertEquals(ChatServiceImpl.REJECTED_TURN_MESSAGE, restored.getMessages().get(restored.getMessages().size() - 1).getContent());
            // A new user turn can start normally, with all original call IDs paired with unexecuted results.
            f.engine.prompt("a new unrelated request").session(restored).call();
            assertEquals(2, f.requests.size());
            assertEquals(batch, toolContents(f.requests.get(1)).size());
            assertTrue(toolContents(f.requests.get(1)).stream().allMatch(s -> s.contains("未执行")));
            assertEquals(0, f.tool.executions.get());
        }
    }

    @Test void skippingBatchGivesModelExplanationInstructionsWithoutExecutingTools() throws Throwable {
        try (Fixture f = new Fixture(2)) {
            AgentSession session = org.noear.solon.ai.agent.session.InMemoryAgentSession.of("skip-test");
            f.engine.prompt("prepare operations").session(session).call();
            HitlDecisionRequest request = new HitlDecisionRequest(); request.setAction("skip");
            request.setComment("先解释影响");
            for (org.noear.solon.ai.agent.react.intercept.HITLTask task : HITL.getPendingTasks(session)) {
                HITL.submit(session, task, new ChatServiceImpl().createDecision(request));
            }
            session.pending(false, null);
            f.engine.prompt((String) null).session(session).call();
            assertEquals(0, f.tool.executions.get()); assertEquals(2, f.requests.size());
            assertEquals(2, toolContents(f.requests.get(1)).size());
            for (String result : toolContents(f.requests.get(1))) {
                assertTrue(result.contains(ChatServiceImpl.SKIPPED_TOOL_MESSAGE));
                assertTrue(result.contains("先解释影响"));
            }
            assertFalse(session.isPending()); assertTrue(HITL.getPendingTasks(session).isEmpty());
        }
    }

    @Test void approvingBatchStillExecutesOriginalTools() throws Throwable {
        try (Fixture f = new Fixture(2)) {
            AgentSession session = org.noear.solon.ai.agent.session.InMemoryAgentSession.of("approve-test");
            f.engine.prompt("prepare operations").session(session).call();
            HitlDecisionRequest request = new HitlDecisionRequest(); request.setAction("approve");
            for (org.noear.solon.ai.agent.react.intercept.HITLTask task : HITL.getPendingTasks(session))
                HITL.submit(session, task, new ChatServiceImpl().createDecision(request));
            session.pending(false, null);
            f.engine.prompt((String) null).session(session).call();
            assertEquals(2, f.tool.executions.get());
            assertEquals(2, f.requests.size());
            assertTrue(toolContents(f.requests.get(1)).stream().allMatch("executed"::equals));
            assertFalse(session.isPending()); assertTrue(HITL.getPendingTasks(session).isEmpty());
        }
    }

    @Test void skipIsEnforcedWhenModelIgnoresInstructionsAndRequestsMoreTools() throws Throwable {
        try (Fixture f = new Fixture(2, true)) {
            SessionRepository repository = new SessionRepository(directory.resolve("guard-sessions"));
            SessionMetadata metadata = repository.create("alice", "Guard test", "stub");
            AgentSession session = repository.getSession(metadata.getSessionId());
            f.engine.prompt("prepare operations").session(session).call();
            ChatServiceImpl service = new ChatServiceImpl();
            WorkspaceMetadata workspace = new WorkspaceMetadata(); workspace.setPath(directory.toString());
            inject(service, "engines", Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{UserHarnessEngineService.class}, (p, method, args) -> f.engine));
            inject(service, "workspaces", Proxy.newProxyInstance(getClass().getClassLoader(),
                    new Class<?>[]{UserWorkspaceService.class}, (p, method, args) -> workspace));
            inject(service, "sessionRepository", repository);
            inject(service, "activeRuns", new ActiveRunRegistry());
            inject(service, "usageAuditRepository", new com.udata.harness.repository.UsageAuditRepository(directory.resolve("audit")));
            HitlDecisionRequest request = new HitlDecisionRequest(); request.setAction("skip");
            request.setSessionId(session.getSessionId());
            List<String> output = service.decide("alice", request).collectList().block(java.time.Duration.ofSeconds(10));
            assertFalse(output.stream().anyMatch(s -> "hitl".equals(ONode.ofJson(s).get("type").getString())), output.toString());
            assertFalse(output.stream().anyMatch(s -> "error".equals(ONode.ofJson(s).get("type").getString())), output.toString());
            assertEquals(0, f.tool.executions.get()); assertEquals(2, f.requests.size());
            assertEquals("none", ONode.ofJson(f.requests.get(1)).get("tool_choice").getString());
            assertFalse(output.stream().anyMatch(s -> ONode.ofJson(s).get("type").getString().startsWith("tool_args_")));
            assertEquals(2, output.stream().filter(s -> "tool_start".equals(ONode.ofJson(s).get("type").getString())).count());
            assertTrue(output.stream().filter(s -> "tool_start".equals(ONode.ofJson(s).get("type").getString()))
                    .allMatch(s -> "skip".equals(ONode.ofJson(s).get("disposition").getString())));
            assertFalse(session.isPending()); assertTrue(HITL.getPendingTasks(session).isEmpty());
            assertTrue(output.stream().anyMatch(s -> s.contains("已阻止继续执行")));
            f.engine.prompt("new explicitly authorized request").session(session).call();
            assertEquals(3, f.requests.size());
            assertFalse("none".equals(ONode.ofJson(f.requests.get(2)).get("tool_choice").getString()));
        }
    }

    private static List<String> toolContents(String request) {
        List<String> result = new ArrayList<>();
        for (ONode item : ONode.ofJson(request).get("messages").getArray())
            if ("tool".equals(item.get("role").getString())) result.add(item.get("content").getString());
        return result;
    }

    private static void inject(Object instance, String name, Object value) throws Exception {
        Field field = instance.getClass().getDeclaredField(name); field.setAccessible(true); field.set(instance, value);
    }

    public static class Tool extends AbsToolProvider {
        final AtomicInteger executions = new AtomicInteger();
        @ToolMapping(name = "mutate_fixture", description = "Test mutation that requires approval")
        public String mutate(@Param("value") String value) { executions.incrementAndGet(); return "executed"; }
    }

    private class Fixture implements AutoCloseable {
        final Tool tool = new Tool();
        final List<String> requests = Collections.synchronizedList(new ArrayList<>());
        final HttpServer server;
        final HarnessEngine engine;
        Fixture(int batch) throws Exception { this(batch, false); }
        Fixture(int batch, boolean hostile) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[4096]; int count;
                    while ((count = exchange.getRequestBody().read(buffer)) >= 0) bytes.write(buffer, 0, count);
                    requests.add(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                    String message;
                    boolean toolResponse = requests.size() == 1 || (hostile && requests.size() == 2);
                    if (toolResponse) {
                        List<String> calls = new ArrayList<>();
                        for (int i = 0; i < batch; i++) calls.add("{\"index\":" + i + ",\"id\":\"call-" + i + "\",\"type\":\"function\",\"function\":{\"name\":\"mutate_fixture\",\"arguments\":\"{\\\"value\\\":\\\"test\\\"}\"}}");
                        message = "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[" + String.join(",", calls) + "]}";
                    } else message = "{\"role\":\"assistant\",\"content\":\"工具未执行，下面解释用途与影响。\"}";
                    String envelope = "{\"id\":\"stub-response\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":" + message
                            + ",\"finish_reason\":\"" + (toolResponse ? "tool_calls" : "stop") + "\"}]}";
                    boolean stream = ONode.ofJson(requests.get(requests.size() - 1)).get("stream").getBoolean();
                    byte[] response = (stream ? "data: " + envelope.replace("\"message\":", "\"delta\":")
                            + "\n\ndata: [DONE]\n\n" : envelope).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
                    exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
                } finally { exchange.close(); }
            });
            server.start();
            ChatConfig config = new ChatConfig(); config.setName("stub"); config.setModel("stub"); config.setProvider("openai");
            config.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
            engine = HarnessEngine.of(directory.toString(), ".test-harness").systemPrompt("Use tools as requested.")
                    .sessionProvider(org.noear.solon.ai.agent.session.InMemoryAgentSession::of)
                    .modelAdd(config).defaultModel("stub").modelRetries(1).maxTurns(4)
                    .extensionAdd((owner, name, builder) -> {
                        builder.defaultToolAdd(tool);
                        builder.defaultInterceptorAdd(100, new HITLInterceptor().onSensitiveTool("mutate_fixture"));
                    }).build();
        }
        @Override public void close() { server.stop(0); }
    }
}
