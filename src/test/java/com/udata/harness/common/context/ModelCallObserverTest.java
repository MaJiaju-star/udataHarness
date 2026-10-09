package com.udata.harness.common.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.event.*;
import org.noear.solon.ai.chat.interceptor.*;
import org.noear.solon.ai.chat.message.ChatMessage;
import reactor.core.publisher.Flux;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class ModelCallObserverTest {
    @TempDir Path directory;
    private ChatResponse response() {
        return (ChatResponse) Proxy.newProxyInstance(ChatResponse.class.getClassLoader(), new Class<?>[]{ChatResponse.class}, (p,m,a) -> {
            switch (m.getName()) {
                case "getUsage": return new AiUsage(100, 0, 20, 120, 40, 60, null);
                case "getModel": return "test";
                case "getMessage": return ChatMessage.ofAssistant("summary");
                case "getContent": case "getText": return "summary";
                default: return null;
            }
        });
    }
    private ChatModel model(ChatInterceptor mock) {
        return ChatModel.of("http://127.0.0.1:1").provider("openai").model("test")
                .contextLength(100000).defaultInterceptorAdd(mock).build();
    }
    private Map<?, ?> bucket(ContextMetrics metrics, String purpose) {
        return (Map<?, ?>) ((Map<?, ?>) metrics.report().get("providerUsageByModelAndPurpose")).get("openai/test/" + purpose);
    }

    @Test void summaryCallsAreMeasuredWithoutChangingSharedModel() throws Exception {
        ContextMetrics metrics = new ContextMetrics(directory);
        ChatModel base = model(new ChatInterceptor() {
            @Override public ChatResponse interceptCall(ChatRequest req, CallChain chain) { return response(); }
        });
        ChatModel observed = new ObservedSummaryModel(base, metrics);
        assertEquals(base.getConfig().getContextLength(), observed.getConfig().getContextLength());
        observed.prompt("first summary").call(); observed.prompt("second summary").call();
        base.prompt("unobserved shared model").call();
        assertEquals(2L, ((Number) bucket(metrics, "summary").get("calls")).longValue());
        assertEquals(200L, ((Number) bucket(metrics, "summary").get("promptTokens")).longValue());
        assertEquals(120L, ((Number) bucket(metrics, "summary").get("cacheReadInputTokens")).longValue());
        assertEquals(0L, ((Number) metrics.report().get("callsWithoutUsage")).longValue());
    }
    @Test void failedAttemptsRemainUnknownAndSeparateFromSuccessfulRetry() throws Exception {
        ContextMetrics metrics = new ContextMetrics(directory);
        ChatModel failed = model(new ChatInterceptor() {
            @Override public ChatResponse interceptCall(ChatRequest req, CallChain chain) throws IOException { throw new IOException("fixture"); }
        });
        assertThrows(IOException.class, () -> new ObservedSummaryModel(failed, metrics).prompt("summary").call());
        ChatModel success = model(new ChatInterceptor() {
            @Override public ChatResponse interceptCall(ChatRequest req, CallChain chain) { return response(); }
        });
        new ObservedSummaryModel(success, metrics).prompt("retry summary").call();
        assertEquals(2L, ((Number) bucket(metrics, "summary").get("calls")).longValue());
        assertEquals(100L, ((Number) bucket(metrics, "summary").get("promptTokens")).longValue());
        assertEquals(1L, ((Number) metrics.report().get("callsWithoutUsage")).longValue());
    }
    @Test void streamingTerminalUsageIsCountedOncePerSubscription() throws Exception {
        CountDownLatch recorded = new CountDownLatch(2);
        ContextMetrics metrics = new ContextMetrics(directory) {
            @Override public synchronized void event(String type, Map<String,Object> detail) {
                super.event(type, detail); if ("model_call".equals(type)) recorded.countDown();
            }
        };
        ChatEvent usage = event(ChatEventType.USAGE), end = event(ChatEventType.STEP_END);
        ChatModel base = model(new ChatInterceptor() {
            @Override public Flux<ChatEvent> interceptStream(ChatRequest req, StreamChain chain) { return Flux.just(usage, usage, end); }
        });
        Flux<ChatEvent> stream = base.prompt("stream").options(o -> o.interceptorAdd(Integer.MIN_VALUE, new ModelCallObserver(metrics, "reason"))).stream();
        stream.collectList().block(); stream.collectList().block();
        assertTrue(recorded.await(2, TimeUnit.SECONDS));
        assertEquals(2L, ((Number) bucket(metrics, "reason").get("calls")).longValue());
        assertEquals(200L, ((Number) bucket(metrics, "reason").get("promptTokens")).longValue());
        assertEquals(120L, ((Number) bucket(metrics, "reason").get("cacheReadInputTokens")).longValue());
        assertEquals(0L, ((Number) metrics.report().get("callsWithoutUsage")).longValue());
    }
    @Test void stepAndResponseEndDoNotCountUsageTwice() throws Exception {
        ContextMetrics metrics = new ContextMetrics(directory);
        ChatModel base = model(new ChatInterceptor() {
            @Override public Flux<ChatEvent> interceptStream(ChatRequest req, StreamChain chain) {
                return Flux.just(event(ChatEventType.STEP_END), event(ChatEventType.RESPONSE_END));
            }
        });
        base.prompt("stream").options(o -> o.interceptorAdd(Integer.MIN_VALUE, new ModelCallObserver(metrics, "reason")))
                .stream().collectList().block();
        assertEquals(1L, ((Number) bucket(metrics, "reason").get("calls")).longValue());
        assertEquals(100L, ((Number) bucket(metrics, "reason").get("promptTokens")).longValue());
    }

    @Test void realFrameworkSseRecordsUsageAndCalibratesPressureAtStepEnd() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        java.util.concurrent.ExecutorService executor = Executors.newSingleThreadExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            String sse = "data: {\"id\":\"fixture\",\"model\":\"test\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"done\"},\"finish_reason\":null}]}\n\n"
                    + "data: {\"id\":\"fixture\",\"model\":\"test\",\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
                    + "data: {\"id\":\"fixture\",\"model\":\"test\",\"choices\":[],\"usage\":{\"prompt_tokens\":10000,\"completion_tokens\":20,\"total_tokens\":10020,\"prompt_cache_hit_tokens\":8000,\"prompt_cache_miss_tokens\":2000}}\n\n"
                    + "data: [DONE]\n\n";
            byte[] bytes = sse.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length);
            try (java.io.OutputStream output = exchange.getResponseBody()) { output.write(bytes); }
            finally { exchange.close(); }
        });
        server.start();
        try {
            AtomicReference<ChatRequest> actualRequest = new AtomicReference<>();
            ChatModel model = ChatModel.of("http://127.0.0.1:" + server.getAddress().getPort()).provider("openai")
                    .model("test").contextLength(100000).defaultInterceptorAdd(new ChatInterceptor() {
                        @Override public Flux<ChatEvent> interceptStream(ChatRequest req, StreamChain chain) {
                            actualRequest.set(req); return chain.doIntercept(req);
                        }
                    }).build();
            com.udata.harness.repository.ModelContextAgentSession session = new com.udata.harness.repository.ModelContextAgentSession("sse", directory.toString());
            class Trace extends org.noear.solon.ai.agent.react.ReActTrace {
                Trace() { prepare(org.noear.solon.ai.agent.react.ReActAgent.of(model).name("main").build().getConfig(),
                        new org.noear.solon.ai.agent.react.ReActOptions(model), session, null, "main"); }
            }
            Trace trace = new Trace(); trace.getWorkingMemory().addMessage(ChatMessage.ofUser("task"));
            TokenPressureMeter.Measurement pressure = session.tokenPressure().measure(trace, "system", trace.getWorkingMemory().getMessages(), .75);
            CountDownLatch recorded = new CountDownLatch(1);
            ContextMetrics metrics = new ContextMetrics(directory) {
                @Override public synchronized void event(String type, Map<String,Object> values) {
                    super.event(type, values); if ("model_call".equals(type)) recorded.countDown();
                }
            };
            List<ChatEvent> events = model.prompt(Arrays.asList(ChatMessage.ofSystem("system"), ChatMessage.ofUser("task")))
                    .options(o -> { o.autoToolCall(false); o.toolContextPut(TokenPressureMeter.FRAME, pressure.frame);
                        o.interceptorAdd(Integer.MIN_VALUE, new ModelCallObserver(metrics, "reason")); })
                    .stream().collectList().block(java.time.Duration.ofSeconds(10));
            assertTrue(recorded.await(2, TimeUnit.SECONDS));
            assertTrue(events.stream().anyMatch(e -> e.getType() == ChatEventType.RESPONSE_END));
            assertEquals(10000L, ((Number) bucket(metrics, "reason").get("promptTokens")).longValue());
            assertEquals(8000L, ((Number) bucket(metrics, "reason").get("cacheReadInputTokens")).longValue());
            assertEquals(1L, ((Number) bucket(metrics, "reason").get("calls")).longValue());
            assertEquals(0L, ((Number) metrics.report().get("callsWithoutUsage")).longValue());
            TokenPressureMeter.Measurement after = session.tokenPressure().measure(trace, "system", trace.getWorkingMemory().getMessages(), .75);
            assertEquals("usage_calibrated", after.basis); assertEquals(10000, after.inputTokens);
            assertNotNull(session.tokenPressure().systems(after.frame.header),
                    String.valueOf(actualRequest.get().getFinalPrompt().getMessages().stream().map(TokenPressureMeter::wireJson).collect(java.util.stream.Collectors.toList())));
        } finally { server.stop(0); executor.shutdownNow(); }
    }
    @Test void cancelledStreamIsRecordedWithUnknownUsage() throws Exception {
        ContextMetrics metrics = new ContextMetrics(directory);
        ChatModel base = model(new ChatInterceptor() {
            @Override public Flux<ChatEvent> interceptStream(ChatRequest req, StreamChain chain) { return Flux.never(); }
        });
        reactor.core.Disposable subscription = base.prompt("stream").options(o -> o.interceptorAdd(Integer.MIN_VALUE, new ModelCallObserver(metrics, "reason"))).stream().subscribe();
        subscription.dispose();
        assertEquals(1L, ((Number) bucket(metrics, "reason").get("calls")).longValue());
        assertEquals(1L, ((Number) metrics.report().get("callsWithoutUsage")).longValue());
    }
    private ChatEvent event(ChatEventType type) {
        return (ChatEvent) Proxy.newProxyInstance(ChatEvent.class.getClassLoader(), new Class<?>[]{ChatEvent.class}, (p,m,a) -> {
            if ("getType".equals(m.getName())) return type;
            if ("getGroup".equals(m.getName())) return type.getGroup();
            if ("getPhase".equals(m.getName())) return type.getPhase();
            if ("getStep".equals(m.getName())) return 0;
            if ("getResponse".equals(m.getName())) return response();
            return null;
        });
    }
}
