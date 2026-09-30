package com.udata.harness.common.context;

import com.udata.harness.common.support.PersistentContextCompressionInterceptor;
import com.udata.harness.repository.ModelContextAgentSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.react.ReActAgent;
import org.noear.solon.ai.chat.*;
import org.noear.solon.ai.chat.interceptor.*;
import org.noear.solon.ai.chat.message.*;
import org.noear.solon.ai.chat.tool.*;
import org.noear.solon.ai.harness.agent.AgentDefinition;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntFunction;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises actual outbound request assembly without connecting to any provider. */
class ContextManagementIntegrationTest {
    @TempDir Path directory;

    @Test void actualAgentRunsAtoBtoAWithCheckpointSearchRestoreAndRestart() throws Throwable {
        ModelContextAgentSession session = session("lifecycle");
        List<String> requests = new ArrayList<>();
        AtomicInteger reads = new AtomicInteger();
        String log = repeat("normal test log\n", 600) + "ERROR MissingSymbol A.java\n" + repeat("normal test log\n", 600);
        ChatModel model = mock(requests, step -> {
            if (step == 0) {
                Map<String, Object> patch = new LinkedHashMap<>();
                patch.put("objective", "Implement A and B"); patch.put("module", "A");
                patch.put("constraints", Collections.singletonList("Preserve public API"));
                return call(step, "task_state_update", Collections.singletonMap("patch", patch));
            }
            if (step <= 10) return call(step, "read", Collections.singletonMap("path", "A.java"));
            if (step == 11) return call(step, "bash", Collections.singletonMap("command", "test A"));
            if (step == 12) return call(step, "task_state_update", Collections.singletonMap("patch", Collections.singletonMap("module", "B")));
            if (step == 13) return call(step, "context_checkpoint", Collections.singletonMap("reason", "A completed; moving to B"));
            if (step == 14) return call(step, "context_search", Collections.singletonMap("query", "MissingSymbol A.java"));
            if (step == 15) {
                Map<String, Object> hit = session.contextArtifacts().search("MissingSymbol A.java", "bash", 1).get(0);
                Map<String, Object> args = new LinkedHashMap<>();
                args.put("id", hit.get("id")); args.put("offset", hit.get("previewOffset")); args.put("maxChars", 1000);
                return call(step, "context_restore", args);
            }
            if (step == 16) return call(step, "task_state_update", Collections.singletonMap("patch", Collections.singletonMap("module", "A")));
            if (step == 17) return call(step, "read", Collections.singletonMap("path", "A.java"));
            return ChatMessage.ofAssistant("Completed A and B; verify tests before delivery");
        });
        PersistentContextCompressionInterceptor interceptor = new PersistentContextCompressionInterceptor(
                1000, 0.75, 1, 2, (m, r, t, old) -> ChatMessage.ofUser("Module A summarized; preserve public API"))
                .contextManagement(1000, 4);
        ReActAgent.Builder builder = ReActAgent.of(model).name(AgentDefinition.AGENT_MAIN).sessionWindowSize(200)
                .maxTurns(40).defaultInterceptorAdd(interceptor)
                .defaultInterceptorAdd(Integer.MIN_VALUE, new ModelCallObserver())
                .defaultToolAdd(function("read", args -> "class A version " + (reads.incrementAndGet() > 10 ? "2" : "1") + "\n" + repeat("source content ", 45)))
                .defaultToolAdd(function("bash", args -> log));
        for (FunctionTool tool : ContextTools.definitions()) builder.defaultToolAdd(tool);
        ReActAgent agent = builder.build();
        agent.prompt("Implement A and B and preserve public API").session(session).call();
        assertEquals(11, reads.get());
        assertEquals(19, requests.size());
        assertTrue(requests.get(3).contains("already present in active context"));
        assertTrue(requests.get(14).contains("Module A summarized"));
        assertTrue(requests.get(14).contains("Implement A and B and preserve public API"));
        assertFalse(requests.get(14).contains("source content"));
        assertTrue(requests.get(16).contains("MissingSymbol A.java"));
        assertTrue(requests.get(18).contains("class A version 2"));
        ModelContextAgentSession reopened = session("lifecycle");
        assertEquals("A", reopened.taskState().get().get("module"));
        assertEquals(Collections.singletonList("Preserve public API"), reopened.taskState().get().get("constraints"));
        assertTrue(reopened.getMessages().stream().anyMatch(m -> log.equals(m.getContent())));
        assertPairs(reopened.getLatestMessages(200));
        agent.prompt("Continue after restart").session(reopened).call();
        assertTrue(requests.get(requests.size() - 1).contains("Module A summarized"));
        assertTrue(requests.get(requests.size() - 1).contains("Continue after restart"));
        Map<?, ?> metrics = reopened.contextMetrics().report();
        assertEquals(20L, ((Number) ((Map<?, ?>) metrics.get("events")).get("model_call")).longValue());
        assertEquals(20L, ((Number) metrics.get("callsWithoutUsage")).longValue());
    }

    @Test void comparisonFixtureMeasuresPayloadReductionWithoutPretendingItIsCacheSavings() throws Throwable {
        Map<String, Object> baseline = comparison(false);
        Map<String, Object> governed = comparison(true);
        long before = ((Number) baseline.get("totalRequestChars")).longValue();
        long after = ((Number) governed.get("totalRequestChars")).longValue();
        assertTrue(after < before / 2, "The fixture should materially reduce actual request payload");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("baseline", baseline); report.put("toolGovernance", governed);
        report.put("requestCharacterReductionRatio", 1D - (double) after / before);
        report.put("realProviderCostMeasured", false);
        report.put("note", "Deterministic mocked-model fixture. Characters are not tokens, cache hits or monetary savings. Both runs retain complete raw logs and produce the same scripted final answer.");
        Path output = Paths.get("target/context-management-comparison.json");
        Files.write(output, ONode.serialize(report).getBytes(StandardCharsets.UTF_8));
    }

    private Map<String, Object> comparison(boolean enabled) throws Throwable {
        String log = repeat("normal test output\n", 2000) + "Tests run: 59, Failures: 0\n";
        List<String> requests = new ArrayList<>();
        ChatModel model = mock(requests, step -> step < 10
                ? call(step, step % 2 == 0 ? "read" : "bash", Collections.singletonMap(step % 2 == 0 ? "path" : "command", "A"))
                : ChatMessage.ofAssistant("done"));
        PersistentContextCompressionInterceptor compression = new PersistentContextCompressionInterceptor(
                1000, 0.75, 1, 2, (m, r, t, old) -> ChatMessage.ofUser("summary"));
        if (enabled) compression.contextManagement(1000, 4);
        ReActAgent.Builder builder = ReActAgent.of(model).name(AgentDefinition.AGENT_MAIN).sessionWindowSize(200)
                .maxTurns(20).defaultInterceptorAdd(compression).defaultToolAdd(function("read", args -> repeat("class A source\n", 40)))
                .defaultToolAdd(function("bash", args -> log));
        if (enabled) for (FunctionTool tool : ContextTools.definitions()) builder.defaultToolAdd(tool);
        ModelContextAgentSession session = session(enabled ? "governed" : "baseline");
        builder.build().prompt("Read A and run tests repeatedly").session(session).call();
        assertEquals("done", session.getLatestMessages(200).get(session.getLatestMessages(200).size() - 1).getContent());
        assertEquals(5, session.getMessages().stream().filter(m -> log.equals(m.getContent())).count());
        assertPairs(session.getLatestMessages(200));
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("requests", requests.size());
        result.put("totalRequestChars", requests.stream().mapToLong(String::length).sum());
        result.put("lastRequestChars", requests.get(requests.size() - 1).length());
        result.put("archiveMessages", session.getMessages().size());
        result.put("activeMessages", session.getLatestMessages(200).size());
        return result;
    }

    private ChatModel mock(List<String> requests, IntFunction<AssistantMessage> responses) {
        AtomicInteger step = new AtomicInteger();
        return ChatModel.of("http://127.0.0.1:1").provider("openai").model("test").contextLength(1000000)
                .defaultInterceptorAdd(new ChatInterceptor() {
                    @Override public ChatResponse interceptCall(ChatRequest request, CallChain chain) {
                        requests.add(request.toRequestData());
                        AssistantMessage message = responses.apply(step.getAndIncrement());
                        return (ChatResponse) Proxy.newProxyInstance(ChatResponse.class.getClassLoader(), new Class<?>[]{ChatResponse.class}, (p, method, args) -> {
                            switch (method.getName()) {
                                case "getMessage": return message;
                                case "getContent": case "getText": return message.getContent();
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

    private AssistantMessage call(int step, String name, Map<String, Object> args) {
        return new AssistantMessage("", null, false, null, null, Collections.singletonList(
                new ToolCall("" + step, "call-" + step, name, ONode.serialize(args), args)), null);
    }
    private FunctionTool function(String name, java.util.function.Function<Map<String, Object>, String> handler) {
        return new FunctionTool() {
            @Override public String name() { return name; }
            @Override public String title() { return name; }
            @Override public String description() { return "Fixture tool"; }
            @Override public boolean returnDirect() { return false; }
            @Override public java.lang.reflect.Type returnType() { return String.class; }
            @Override public String inputSchema() { return "{\"type\":\"object\",\"properties\":{\"path\":{\"type\":\"string\"},\"command\":{\"type\":\"string\"}}}"; }
            @Override public Object handle(Map<String, Object> args) { return handler.apply(args); }
        };
    }
    private void assertPairs(List<ChatMessage> messages) {
        Set<String> pending = new HashSet<>();
        for (ChatMessage message : messages) {
            if (message instanceof AssistantMessage && message.isToolCalls()) {
                assertTrue(pending.isEmpty());
                for (ToolCall call : ((AssistantMessage) message).getToolCalls()) pending.add(call.getId());
            } else if (message instanceof ToolMessage) assertTrue(pending.remove(((ToolMessage) message).getToolCallId()));
            else assertTrue(pending.isEmpty());
        }
        assertTrue(pending.isEmpty());
    }
    private ModelContextAgentSession session(String id) { return new ModelContextAgentSession(id, directory.resolve(id).toString()); }
    private static String repeat(String value, int count) { StringBuilder b = new StringBuilder(); for (int i = 0; i < count; i++) b.append(value); return b.toString(); }
}
