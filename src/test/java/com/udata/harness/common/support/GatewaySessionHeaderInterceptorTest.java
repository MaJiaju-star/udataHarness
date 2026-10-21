package com.udata.harness.common.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatModel;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.AgentDefinition;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

class GatewaySessionHeaderInterceptorTest {
    @TempDir Path root;

    @Test void sharedEngineKeepsConcurrentSessionsAndLaterTurnsIsolated() throws Throwable {
        CountDownLatch both = new CountDownLatch(2);
        try (Gateway gateway = new Gateway(request -> {
            both.countDown();
            try { if (!both.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Requests did not overlap"); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
            return answer("done");
        })) {
            HarnessEngine engine = engine(gateway);
            AgentSession a = InMemoryAgentSession.of("root-A"), b = InMemoryAgentSession.of("root-B");
            CompletableFuture<Void> one = CompletableFuture.runAsync(() -> call(engine, a, "request-A", false));
            CompletableFuture<Void> two = CompletableFuture.runAsync(() -> call(engine, b, "request-B", true));
            CompletableFuture.allOf(one, two).get(15, TimeUnit.SECONDS);
            call(engine, a, "request-A-next", true);
            call(engine, InMemoryAgentSession.of("root-A"), "request-A-restarted", false);
            assertEquals(4, gateway.requests.size());
            for (Request request : gateway.requests) {
                assertEquals(Collections.singletonList(request.body.contains("request-A") ? "root-A" : "root-B"), request.ids);
                assertFalse(request.body.contains("root-A"));
                assertFalse(request.body.contains("root-B"));
            }
        }
    }

    @Test void taskAndMultitaskUseRootIdentityInSyncAndStreamingParents() throws Throwable {
        for (boolean stream : Arrays.asList(false, true)) {
            for (boolean multi : Arrays.asList(false, true)) {
                AtomicInteger mainCalls = new AtomicInteger();
                try (Gateway gateway = new Gateway(request -> {
                    ONode body = ONode.ofJson(request.body);
                    String firstUser = body.get("messages").getArray().stream()
                            .filter(message -> "user".equals(message.get("role").getString()))
                            .findFirst().get().get("content").getString();
                    if (!firstUser.contains("dispatch-root")) return answer("child finished");
                    if (mainCalls.getAndIncrement() > 0) return answer("all finished");
                    String task = "{\"agent_name\":\"worker\",\"description\":\"test\",\"prompt\":\"child-work\"}";
                    String args = multi ? "{\"tasks\":[" + task + "," + task + "]}" : task;
                    return "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"dispatch\",\"type\":\"function\","
                            + "\"function\":{\"name\":\"" + (multi ? "multitask" : "task") + "\",\"arguments\":" + ONode.serialize(args) + "}}]}";
                })) {
                    HarnessEngine engine = engine(gateway);
                    engine.getAgentManager().addAgent(AgentDefinition.builder().name("worker")
                            .description("Test worker").systemPrompt("Finish the child work.").build());
                    String id = "root-task-" + stream + "-" + multi;
                    call(engine, InMemoryAgentSession.of(id), "dispatch-root", stream);
                    assertEquals(multi ? 4 : 3, gateway.requests.size(), "Must actually call the child model(s)");
                    assertTrue(gateway.requests.stream().allMatch(request -> Collections.singletonList(id).equals(request.ids)));
                }
            }
        }
    }

    @Test void modelRetryKeepsTheSameSingleHeader() throws Throwable {
        try (Gateway gateway = new Gateway(request -> answer("done"))) {
            gateway.failures.set(1);
            HarnessEngine engine = engine(gateway);
            engine.prompt("retry please").session(InMemoryAgentSession.of("root-retry"))
                    .options(options -> options.retryConfig(2, 1)).call();
            assertEquals(2, gateway.requests.size());
            assertTrue(gateway.requests.stream().allMatch(request -> Collections.singletonList("root-retry").equals(request.ids)));
        }
    }

    @Test void nestedChildrenInheritRootRatherThanImmediateParentId() {
        AgentSession parent = InMemoryAgentSession.of("root"), child = InMemoryAgentSession.of("child"), nested = InMemoryAgentSession.of("nested");
        GatewaySessionHeaderInterceptor.inherit(parent, child);
        GatewaySessionHeaderInterceptor.inherit(child, nested);
        assertEquals("root", GatewaySessionHeaderInterceptor.rootSessionId(nested));
        assertEquals("nested", nested.getSessionId());
    }

    @Test void headerWorksAcrossOpenAiResponsesAndAnthropicProtocols() throws Throwable {
        for (String provider : Arrays.asList("openai", "openai-responses", "anthropic")) {
            try (Gateway gateway = new Gateway(request -> answer("done"))) {
                gateway.protocol = provider;
                ChatConfig config = gateway.config(); config.setProvider(provider);
                ChatModel model = ChatModel.of(config).build();
                model.prompt("protocol check").options(options -> options.httpCustomizeAdd(
                        http -> GatewaySessionHeaderInterceptor.apply(http, "root-protocol"))).call();
                assertEquals(1, gateway.requests.size());
                assertEquals(Collections.singletonList("root-protocol"), gateway.requests.get(0).ids);
            }
        }
    }

    private HarnessEngine engine(Gateway gateway) {
        return HarnessEngine.of(root.toString(), ".soloncode").sessionProvider(InMemoryAgentSession::of)
                .modelAdd(gateway.config()).defaultModel("stub").systemPrompt("Complete the request.")
                .toolsAdd(Collections.singletonList("task")).subagentEnabled(true).maxTurns(5).modelRetries(1)
                .extensionAdd((owner, name, builder) -> builder.defaultInterceptorAdd(Integer.MIN_VALUE,
                        new GatewaySessionHeaderInterceptor())).build();
    }

    private static void call(HarnessEngine engine, AgentSession session, String prompt, boolean stream) {
        try {
            if (stream) engine.prompt(prompt).session(session).stream().collectList().block();
            else engine.prompt(prompt).session(session).call();
        } catch (Throwable e) { throw new CompletionException(e); }
    }

    private static String answer(String text) {
        return "{\"role\":\"assistant\",\"content\":" + ONode.serialize(text) + "}";
    }

    private static final class Request {
        final String body;
        final List<String> ids;
        Request(String body, List<String> ids) { this.body = body; this.ids = ids; }
    }

    private static final class Gateway implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor = Executors.newCachedThreadPool();
        final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
        final AtomicInteger failures = new AtomicInteger();
        String protocol = "openai";
        Gateway(Function<Request, String> response) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                try {
                    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                    byte[] buffer = new byte[4096]; int count;
                    while ((count = exchange.getRequestBody().read(buffer)) != -1) bytes.write(buffer, 0, count);
                    List<String> ids = exchange.getRequestHeaders().get("session-id");
                    Request request = new Request(new String(bytes.toByteArray(), StandardCharsets.UTF_8),
                            ids == null ? Collections.emptyList() : new ArrayList<>(ids));
                    requests.add(request);
                    if (failures.getAndDecrement() > 0) { send(exchange, 503, "application/json", "{\"error\":{\"message\":\"retry\"}}"); return; }
                    String message = response.apply(request);
                    if ("anthropic".equals(protocol)) {
                        send(exchange, 200, "application/json", "{\"id\":\"stub\",\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"done\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":10,\"output_tokens\":1}}");
                    } else if ("openai-responses".equals(protocol)) {
                        send(exchange, 200, "application/json", "{\"id\":\"stub\",\"object\":\"response\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"done\"}]}]}");
                    } else {
                        boolean stream = ONode.ofJson(request.body).get("stream").getBoolean();
                        boolean tool = message.contains("\"tool_calls\"");
                        String finish = tool ? "tool_calls" : "stop";
                        String payload = stream
                                ? "data: {\"id\":\"stub\",\"choices\":[{\"index\":0,\"delta\":" + message + ",\"finish_reason\":null}]}\n\n"
                                  + "data: {\"id\":\"stub\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"" + finish + "\"}]}\n\ndata: [DONE]\n\n"
                                : "{\"id\":\"stub\",\"choices\":[{\"index\":0,\"message\":" + message + ",\"finish_reason\":\"" + finish + "\"}]}";
                        send(exchange, 200, stream ? "text/event-stream" : "application/json", payload);
                    }
                } catch (Throwable e) { send(exchange, 500, "text/plain", "Stub failed"); }
                finally { exchange.close(); }
            });
            server.start();
        }
        ChatConfig config() {
            ChatConfig config = new ChatConfig(); config.setName("stub"); config.setModel("stub"); config.setProvider("openai");
            config.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
            config.setContextLength(128000L); return config;
        }
        private static void send(HttpExchange exchange, int status, String type, String text) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", type);
            exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
        }
        @Override public void close() { server.stop(0); executor.shutdownNow(); }
    }
}
