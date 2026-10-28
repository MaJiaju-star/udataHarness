package com.udata.harness.common.context;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.interceptor.*;
import org.noear.solon.ai.harness.HarnessEngine;
import reactor.core.publisher.Flux;
import java.io.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class FinalRequestPrefixInterceptorTest {
    @TempDir Path root;

    @Test void capturesLateMutationsMatchesWireAndPreservesBaselineAcrossEngineRestart() throws Throwable {
        List<String> wire = new ArrayList<>(); AtomicInteger reads = new AtomicInteger(800);
        HttpServer server = server(wire, reads, false);
        try {
            ContextArtifactStore store = new ContextArtifactStore(root.resolve("artifacts"));
            AgentSession session = InMemoryAgentSession.of("main-session");
            HarnessEngine engine = engine(server, store, true);
            engine.prompt("PAYLOAD_SECRET").session(session).call();
            reads.set(200);
            // Reusing an engine is not required for the durable diagnostic baseline.
            engine(server, new ContextArtifactStore(root.resolve("artifacts")), true).prompt("PAYLOAD_SECRET").session(session).stream().blockLast();
            List<Map<String, Object>> events = store.requestDiagnostics("main-session", 20);
            assertEquals(2, events.size());
            assertEquals("first_request", events.get(0).get("assessment"));
            assertEquals("stable_prefix_lower_cache_read", events.get(1).get("assessment"));
            assertEquals(true, events.get(1).get("prefixPreserved"));
            assertEquals(events.get(0).get("id"), events.get(1).get("previousRequestId"));
            for (int i = 0; i < wire.size(); i++) {
                assertEquals("none", ONode.ofJson(wire.get(i)).get("tool_choice").getString());
                assertEquals(ContextArtifactStore.digest(ONode.serialize(ONode.ofJson(wire.get(i)).toBean(Map.class))), events.get(i).get("requestHash"));
            }
            reads.set(0); engine.prompt("PAYLOAD_SECRET").session(session).call();
            Map<String, Object> zero = store.requestDiagnostics("main-session", 20).get(2);
            assertEquals(0L, ((Number) ((Map<?, ?>) zero.get("usage")).get("cacheReadTokens")).longValue());
            assertNotEquals("cache_usage_unknown", zero.get("assessment"));
            engine.prompt("other").session(InMemoryAgentSession.of("isolated")).call();
            assertEquals("first_request", store.requestDiagnostics("isolated", 20).get(0).get("assessment"));
            try (java.util.stream.Stream<Path> files = Files.walk(root.resolve("artifacts"))) {
                for (Path file : (Iterable<Path>) files::iterator)
                    if (Files.isRegularFile(file)) assertFalse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains("PAYLOAD_SECRET"));
            }
        } finally { server.stop(0); }
    }

    @Test void physicalRetriesAreRecordedWithoutCountingMissingUsageAsZero() throws Throwable {
        List<String> wire = new ArrayList<>(); HttpServer server = server(wire, new AtomicInteger(0), true);
        try {
            ContextArtifactStore store = new ContextArtifactStore(root.resolve("artifacts"));
            engine(server, store, true).prompt("test").session(InMemoryAgentSession.of("retry")).options(o -> o.retryConfig(2, 1)).call();
            List<Map<String, Object>> events = store.requestDiagnostics("retry", 20);
            assertEquals(2, events.size());
            assertEquals("error", events.get(0).get("status")); assertEquals("success", events.get(1).get("status"));
            assertEquals("identical", events.get(1).get("change"));
            assertEquals("cache_usage_unknown", events.get(1).get("assessment"));
            assertNull(events.get(1).get("previousCacheReadTokens"));
        } finally { server.stop(0); }
    }

    @Test void growingInputIsDistinguishedFromLostCacheReads() throws Throwable {
        List<String> wire = new ArrayList<>(); AtomicInteger reads = new AtomicInteger(800), input = new AtomicInteger(1000);
        HttpServer server = server(wire, reads, false, input);
        try {
            ContextArtifactStore store = new ContextArtifactStore(root.resolve("artifacts"));
            HarnessEngine engine = engine(server, store, true); AgentSession session = InMemoryAgentSession.of("growth");
            engine.prompt("one").session(session).call(); reads.set(900); input.set(2000);
            engine.prompt("two").session(session).stream().blockLast();
            Map<String, Object> event = store.requestDiagnostics("growth", 20).get(1);
            assertEquals("appended_input_growth", event.get("assessment")); assertEquals(false, event.get("cacheReadDecreased"));
        } finally { server.stop(0); }
    }

    @Test void disabledDiagnosticsDoNotWriteOrChangeRequests() throws Throwable {
        List<String> wire = new ArrayList<>(); HttpServer server = server(wire, new AtomicInteger(800), false);
        try {
            ContextArtifactStore store = new ContextArtifactStore(root.resolve("artifacts"));
            engine(server, store, false).prompt("test").session(InMemoryAgentSession.of("disabled")).call();
            assertEquals(1, wire.size()); assertTrue(store.requestDiagnostics("disabled", 20).isEmpty());
        } finally { server.stop(0); }
    }

    @Test void diagnosticIoFailureCannotFailAModelRequest() throws Throwable {
        List<String> wire = new ArrayList<>(); HttpServer server = server(wire, new AtomicInteger(800), false);
        try {
            Files.write(root.resolve("not-a-directory"), new byte[]{1});
            ContextArtifactStore store = new ContextArtifactStore(root.resolve("not-a-directory"));
            assertNotNull(engine(server, store, true).prompt("test").session(InMemoryAgentSession.of("io-failure")).call());
            assertEquals(1, wire.size());
        } finally { server.stop(0); }
    }

    @Test void actualClaudeAndResponsesRequestsAreFingerprintedInTheirWireFormats() throws Throwable {
        for (String provider : Arrays.asList("anthropic", "openai-responses")) {
            List<String> wire = new ArrayList<>();
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
                    while ((n = exchange.getRequestBody().read(buf)) >= 0) out.write(buf, 0, n);
                    wire.add(new String(out.toByteArray(), StandardCharsets.UTF_8));
                    String payload = "anthropic".equals(provider)
                            ? "{\"id\":\"msg-test\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"local\",\"content\":[{\"type\":\"text\",\"text\":\"done\"}],\"stop_reason\":\"end_turn\",\"usage\":{\"input_tokens\":200,\"output_tokens\":1,\"cache_read_input_tokens\":800}}"
                            : "{\"id\":\"resp-test\",\"object\":\"response\",\"status\":\"completed\",\"model\":\"local\",\"output\":[{\"type\":\"message\",\"id\":\"msg\",\"role\":\"assistant\",\"status\":\"completed\",\"content\":[{\"type\":\"output_text\",\"text\":\"done\",\"annotations\":[]}]}],\"usage\":{\"input_tokens\":1000,\"output_tokens\":1,\"total_tokens\":1001,\"input_tokens_details\":{\"cached_tokens\":800}}}";
                    byte[] bytes = payload.getBytes(StandardCharsets.UTF_8); exchange.getResponseHeaders().set("Content-Type", "application/json");
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                } finally { exchange.close(); }
            }); server.start();
            try {
                ContextArtifactStore store = new ContextArtifactStore(root.resolve(provider));
                ChatConfig config = new ChatConfig(); config.setName("local"); config.setModel("local"); config.setProvider(provider);
                config.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort());
                HarnessEngine engine = HarnessEngine.of(root.toString(), ".soloncode").systemPrompt("stable").toolsAdd(Collections.emptyList())
                        .modelAdd(config).defaultModel("local").modelRetries(1).sessionProvider(InMemoryAgentSession::of)
                        .extensionAdd((owner, name, agent) -> agent.defaultInterceptorAdd(Integer.MAX_VALUE,
                                new FinalRequestPrefixInterceptor(store, new ContextCacheConfiguration()))).build();
                AgentSession session = InMemoryAgentSession.of(provider);
                engine.prompt("one").session(session).call(); engine.prompt("two").session(session).call();
                List<Map<String, Object>> events = store.requestDiagnostics(provider, 20);
                assertEquals(2, events.size()); assertEquals("anthropic".equals(provider) ? "anthropic" : "responses", events.get(1).get("format"));
                assertEquals(true, events.get(1).get("prefixPreserved"));
                assertEquals(800L, ((Number) ((Map<?, ?>) events.get(1).get("usage")).get("cacheReadTokens")).longValue());
                assertEquals(ContextArtifactStore.digest(ONode.serialize(ONode.ofJson(wire.get(1)).toBean(Map.class))), events.get(1).get("requestHash"));
            } finally { server.stop(0); }
        }
    }

    private HarnessEngine engine(HttpServer server, ContextArtifactStore store, boolean enabled) {
        ChatConfig config = new ChatConfig(); config.setName("local"); config.setModel("local"); config.setProvider("openai");
        config.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        ContextCacheConfiguration options = new ContextCacheConfiguration(); options.prefixDiagnosticsEnabled = enabled;
        return HarnessEngine.of(root.toString(), ".soloncode").systemPrompt("stable").toolsAdd(Collections.emptyList())
                .modelAdd(config).defaultModel("local").modelRetries(2).sessionProvider(InMemoryAgentSession::of)
                .extensionAdd((owner, name, agent) -> {
                    agent.defaultInterceptorAdd(Integer.MAX_VALUE, new FinalRequestPrefixInterceptor(store, options));
                    agent.defaultInterceptorAdd(Integer.MAX_VALUE, new LateMutation());
                }).build();
    }
    public static class LateMutation implements ReActInterceptor {
        @Override public ChatResponse interceptCall(ChatRequest request, CallChain chain) throws IOException {
            request.getOptions().tool_choice("none"); ChatResponse response = chain.doIntercept(request);
            request.getOptions().tool_choice("auto"); return response;
        }
        @Override public Flux<ChatEvent> interceptStream(ChatRequest request, StreamChain chain) {
            request.getOptions().tool_choice("none");
            return chain.doIntercept(request).doOnComplete(() -> request.getOptions().tool_choice("auto"));
        }
    }
    private HttpServer server(List<String> wire, AtomicInteger reads, boolean retry) throws IOException {
        return server(wire, reads, retry, new AtomicInteger(1000));
    }
    private HttpServer server(List<String> wire, AtomicInteger reads, boolean retry, AtomicInteger input) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] buf = new byte[4096]; int n;
                while ((n = exchange.getRequestBody().read(buf)) >= 0) out.write(buf, 0, n);
                String body = new String(out.toByteArray(), StandardCharsets.UTF_8); wire.add(body);
                boolean fail = retry && wire.size() == 1, stream = ONode.ofJson(body).get("stream").getBoolean();
                String payload = fail ? "{\"error\":{\"message\":\"temporary outage\"}}"
                        : "{\"id\":\"test\",\"choices\":[{\"index\":0,\"" + (stream ? "delta" : "message") + "\":{\"role\":\"assistant\",\"content\":\"done\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":" + input.get() + ",\"completion_tokens\":1,\"total_tokens\":" + (input.get() + 1)
                        + (retry ? "" : ",\"prompt_tokens_details\":{\"cached_tokens\":" + reads.get() + "}") + "}}";
                byte[] bytes = (!fail && stream ? "data: " + payload + "\n\ndata: [DONE]\n\n" : payload).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", !fail && stream ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(fail ? 503 : 200, bytes.length); exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        }); server.start(); return server;
    }
}
