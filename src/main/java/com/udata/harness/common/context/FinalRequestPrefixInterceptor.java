package com.udata.harness.common.context;

import org.noear.snack4.ONode;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.ChatRequest;
import org.noear.solon.ai.chat.ChatResponse;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.ChatConfigReadonly;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.ChatSession;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.chat.event.ChatEvent;
import org.noear.solon.ai.chat.event.ChatEventType;
import org.noear.solon.ai.chat.interceptor.CallChain;
import org.noear.solon.ai.chat.interceptor.StreamChain;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Read-only diagnostic of physical requests, with a durable per-session/per-agent baseline. */
public final class FinalRequestPrefixInterceptor implements ReActInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(FinalRequestPrefixInterceptor.class);
    private static final String CONTEXT = "__udataRequestPrefixDiagnostic";
    private final ContextArtifactStore store;
    private final ContextCacheConfiguration configuration;
    public FinalRequestPrefixInterceptor(ContextArtifactStore store, ContextCacheConfiguration configuration) {
        this.store = store; this.configuration = configuration;
    }
    @Override public void onReasonStart(ReActTrace trace, StringBuilder systemPrompt) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("sessionId", trace.getSession().getSessionId()); context.put("agentName", trace.getAgentName());
        context.put("runId", trace.getRunId()); context.put("reasonId", trace.getCurrentTurnId());
        trace.getOptions().getModelOptions().toolContextPut(CONTEXT, Collections.unmodifiableMap(context));
    }

    @Override public ChatResponse interceptCall(ChatRequest request, CallChain chain) throws IOException {
        if (!configuration.prefixDiagnosticsEnabled || !(request.getOptions().toolContext().get(CONTEXT) instanceof Map))
            return chain.doIntercept(request);
        ObservedRequest observed = new ObservedRequest(request);
        long started = System.currentTimeMillis();
        try {
            ChatResponse response = chain.doIntercept(observed);
            finish(observed, started, response == null ? "empty" : response.getError() == null ? "success" : "error", response == null ? null : response.getUsage());
            return response;
        } catch (IOException | RuntimeException error) {
            finish(observed, started, "error", null); throw error;
        }
    }
    @Override public Flux<ChatEvent> interceptStream(ChatRequest request, StreamChain chain) {
        if (!configuration.prefixDiagnosticsEnabled || !(request.getOptions().toolContext().get(CONTEXT) instanceof Map))
            return chain.doIntercept(request);
        ObservedRequest observed = new ObservedRequest(request);
        long started = System.currentTimeMillis();
        AtomicReference<AiUsage> usage = new AtomicReference<>(); AtomicBoolean recorded = new AtomicBoolean();
        try {
            return chain.doIntercept(observed).doOnNext(event -> {
                if (event.is(ChatEventType.STEP_END, ChatEventType.RESPONSE_END)) {
                    if (event.getUsage() != null) usage.set(event.getUsage());
                    else if (event.getResponse() != null && event.getResponse().getUsage() != null) usage.set(event.getResponse().getUsage());
                }
            }).doOnComplete(() -> {
                if (recorded.compareAndSet(false, true)) finish(observed, started, "success", usage.get());
            }).doOnError(error -> {
                if (recorded.compareAndSet(false, true)) finish(observed, started, "error", usage.get());
            }).doOnCancel(() -> {
                if (recorded.compareAndSet(false, true)) finish(observed, started, "cancelled", usage.get());
            });
        } catch (RuntimeException error) {
            if (recorded.compareAndSet(false, true)) finish(observed, started, "error", null);
            throw error;
        }
    }

    private void finish(ObservedRequest request, long timestamp, String status, AiUsage usage) {
        if (!configuration.prefixDiagnosticsEnabled) return;
        Object metadata = request.getOptions().toolContext().get(CONTEXT);
        if (!(metadata instanceof Map)) return; // Summaries and unrelated model calls have no Reason identity.
        try {
            Map<String, Object> context = (Map<String, Object>) metadata;
            Map<String, Object> captured = request.snapshot.get();
            if (captured == null) return; // A short-circuited or invalid request never reached serialization.
            Map<String, Object> current = new LinkedHashMap<>(captured);
            current.put("id", UUID.randomUUID().toString()); current.put("timestamp", timestamp); current.put("status", status);
            String session = String.valueOf(context.get("sessionId")), agent = String.valueOf(context.get("agentName"));
            Path state = store.sessionDirectory(session).resolve("request-prefix-" + ContextArtifactStore.digest(agent) + ".json");
            synchronized (store) {
                Map<String, Object> previous = read(state);
                Map<String, Object> event = RequestPrefixSnapshot.compare(previous, current);
                event.putAll(context); event.put("id", current.get("id")); event.put("timestamp", timestamp);
                event.put("status", status); event.put("format", current.get("format"));
                event.put("requestHash", current.get("requestHash"));
                Map<String, Object> normalized = usage == null ? Collections.emptyMap()
                        : ContextUsage.normalize(usage, configuration.policy(request.getConfig().getNameOrModel()).prices);
                event.put("usage", normalized);
                Map<?, ?> oldUsage = previous != null && previous.get("usage") instanceof Map ? (Map<?, ?>) previous.get("usage") : Collections.emptyMap();
                event.put("previousCacheReadTokens", oldUsage.get("cacheReadTokens"));
                event.put("previousCacheHitRate", oldUsage.get("cacheHitRate"));
                event.put("previousInputTokens", oldUsage.get("inputTokens"));
                if (normalized.get("cacheReadTokens") instanceof Number && oldUsage.get("cacheReadTokens") instanceof Number)
                    event.put("cacheReadDecreased", RequestPrefixSnapshot.number(normalized.get("cacheReadTokens")) < RequestPrefixSnapshot.number(oldUsage.get("cacheReadTokens")));
                event.put("assessment", assessment(event, normalized));
                current.put("usage", normalized);
                store.recordRequestDiagnostic(session, event);
                write(state, current);
            }
        } catch (Exception error) {
            // Diagnostics must not affect successful requests or replace the actual model error.
            LOG.warn("Request prefix diagnostic unavailable ({})", error.getClass().getSimpleName());
        }
    }

    /** Delegate every request property and return the exact same body from the SDK's send serialization.
     * Capturing here avoids reserializing mutable tool definitions after a slow response. */
    private static final class ObservedRequest extends ChatRequest {
        private final ChatRequest delegate;
        private final AtomicReference<Map<String, Object>> snapshot = new AtomicReference<>();
        ObservedRequest(ChatRequest delegate) {
            super(new ChatConfig(), null, delegate.getOptions(), delegate.getSession(), null, null, delegate.isStream());
            this.delegate = delegate;
        }
        @Override public String getAgentAndModel() { return delegate.getAgentAndModel(); }
        @Override public ChatConfigReadonly getConfig() { return delegate.getConfig(); }
        @Override public ChatOptions getOptions() { return delegate.getOptions(); }
        @Override public ChatSession getSession() { return delegate.getSession(); }
        @Override public boolean isStream() { return delegate.isStream(); }
        @Override public Prompt getOriginalPrompt() { return delegate.getOriginalPrompt(); }
        @Override public Prompt getFinalPrompt() { return delegate.getFinalPrompt(); }
        @Override public String toRequestData() {
            String body = delegate.toRequestData();
            try { snapshot.set(RequestPrefixSnapshot.capture(body, getConfig().getProvider(), getConfig().getApiUrl())); }
            catch (RuntimeException error) { LOG.warn("Request fingerprint unavailable ({})", error.getClass().getSimpleName()); }
            return body;
        }
    }

    private static String assessment(Map<String, Object> event, Map<String, Object> usage) {
        if (!"success".equals(event.get("status"))) return "request_not_successful";
        if (!Boolean.TRUE.equals(event.get("baselineAvailable"))) return "first_request";
        if (usage.get("cacheReadTokens") == null) return "cache_usage_unknown";
        if (!Boolean.TRUE.equals(event.get("prefixPreserved"))) return String.valueOf(event.get("change"));
        if (Boolean.TRUE.equals(event.get("cacheReadDecreased"))) return "stable_prefix_lower_cache_read";
        if (usage.get("cacheHitRate") instanceof Number && event.get("previousCacheHitRate") instanceof Number
                && ((Number) usage.get("cacheHitRate")).doubleValue() < ((Number) event.get("previousCacheHitRate")).doubleValue()
                && RequestPrefixSnapshot.number(usage.get("inputTokens")) > RequestPrefixSnapshot.number(event.get("previousInputTokens")))
            return "appended_input_growth";
        return "prefix_preserved";
    }
    private static Map<String, Object> read(Path file) {
        try {
            if (!Files.isRegularFile(file)) return null;
            Map<String, Object> data = ONode.ofJson(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)).toBean(Map.class);
            return RequestPrefixSnapshot.number(data.get("version")) == 1 ? data : null;
        } catch (IOException | RuntimeException ignored) { return null; }
    }
    private static void write(Path file, Map<String, Object> state) throws IOException {
        Files.createDirectories(file.getParent()); Path temporary = Files.createTempFile(file.getParent(), "prefix-", ".tmp");
        try {
            Files.write(temporary, ONode.serialize(state).getBytes(StandardCharsets.UTF_8));
            try { Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary); }
    }
}
