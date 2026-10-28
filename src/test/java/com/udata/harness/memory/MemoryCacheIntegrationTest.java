package com.udata.harness.memory;

import com.sun.net.httpserver.HttpServer;
import com.udata.harness.common.context.CacheAlignedCompressionInterceptor;
import com.udata.harness.common.context.ContextMessageIds;
import com.udata.harness.common.support.StableToolDefinitionsInterceptor;
import com.udata.harness.common.support.WorkspaceInstructionsInterceptor;
import com.udata.harness.repository.CompactingFileAgentSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.tool.AbsToolProvider;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.AgentDefinition;
import org.noear.snack4.ONode;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class MemoryCacheIntegrationTest {
    @TempDir Path root;
    private final Map<String, AgentSession> sessions = new java.util.concurrent.ConcurrentHashMap<>();
    private static final String SUMMARY = "## Primary Request and Intent\n- Finish the actual user task.\n"
            + "## Confirmed Facts and User Constraints\n(none)\n## Files and Code\n(none)\n"
            + "## Errors and Failed Attempts\n(none)\n## Current Work and Pending Jobs\n- Verify results.\n"
            + "## Next Step\n- Finish verification.\n## Critical Context\n(none)\n"
            + "## Current Turn Intent\n- Continue the original user task.";

    @Test void recallAndPermissionChangesKeepSystemAndSchemasStableAndOnlyAppendChangedSnapshots() throws Throwable {
        MemoryService memories = memories();
        try (Gateway gateway = new Gateway(body -> answer("done"))) {
            HarnessEngine engine = engine(gateway, false, null);
            AgentSession session = InMemoryAgentSession.of("stable-recall");
            for (String prompt : Arrays.asList("alpha", "beta", "请记住 beta", "请忘记 beta"))
                call(engine, session, memories, prompt, false);
            assertEquals(2, recalls(session).size());
            assertTrue(latestRecall(gateway.requests.get(0)).contains("ALPHA_RECALL_MARKER"));
            assertTrue(latestRecall(gateway.requests.get(1)).contains("BETA_RECALL_MARKER"));
            assertFalse(latestRecall(gateway.requests.get(1)).contains("ALPHA_RECALL_MARKER"));
            for (int i = 1; i < gateway.requests.size(); i++) {
                assertEquals(system(gateway.requests.get(0)), system(gateway.requests.get(i)));
                assertEquals(tools(gateway.requests.get(0)), tools(gateway.requests.get(i)));
                assertPrefix(gateway.requests.get(i - 1), gateway.requests.get(i));
            }
            call(engine, session, memories, "unrelated", true);
            assertTrue(latestRecall(gateway.requests.get(4)).contains("No relevant memories were recalled"));
            assertEquals(3, recalls(session).size());
            assertEquals(system(gateway.requests.get(0)), system(gateway.requests.get(4)));
            assertEquals(tools(gateway.requests.get(0)), tools(gateway.requests.get(4)));
            assertFalse(system(gateway.requests.get(0)).contains("ALPHA_RECALL_MARKER"));
            List<String> names = new ArrayList<>();
            for (ONode tool : ONode.ofJson(gateway.requests.get(0)).get("tools").getArray()) names.add(tool.get("function").get("name").getString());
            List<String> sorted = new ArrayList<>(names); Collections.sort(sorted); assertEquals(sorted, names);
            assertTrue(names.containsAll(Arrays.asList("memory_search", "memory_save", "memory_forget")));
            assertTrue(recalls(session).stream().allMatch(ContextMessageIds::internal));
        }
    }

    @Test void visibleWriteAndDeleteToolsStillRejectUnauthorizedModelCalls() throws Throwable {
        MemoryService memories = memories(); AtomicInteger calls = new AtomicInteger();
        try (Gateway gateway = new Gateway(body -> {
            int step = calls.getAndIncrement();
            if (step == 0) return tool("memory_save", "{\"key\":\"unauthorized\",\"content\":\"MUST_NOT_SAVE\",\"scope\":\"project\"}");
            if (step == 1) return tool("memory_forget", "{\"id\":\"unknown\",\"revision\":1}");
            return answer("done");
        })) {
            AgentSession session = InMemoryAgentSession.of("denied-memory");
            call(engine(gateway, false, null), session, memories, "inspect alpha", false);
            assertEquals(3, gateway.requests.size());
            List<ONode> evidence = ONode.ofJson(gateway.requests.get(2)).get("messages").getArray();
            assertTrue(evidence.stream().anyMatch(m -> "tool".equals(m.get("role").getString()) && m.get("content").getString().contains("未获得明确记忆写入请求")));
            assertTrue(evidence.stream().anyMatch(m -> "tool".equals(m.get("role").getString()) && m.get("content").getString().contains("未获得明确删除记忆请求")));
            assertFalse(ONode.serialize(memories.list("alice", "ws-a")).contains("MUST_NOT_SAVE"));
            assertTrue(tools(gateway.requests.get(0)).contains("memory_save"));
            assertEquals(tools(gateway.requests.get(0)), tools(gateway.requests.get(2)));
        }
    }

    @Test void realCompactionRestoresRecallAndItsHotPrefixAndRestartUsesTheNewSnapshot() throws Throwable {
        MemoryService memories = memories(); AtomicInteger ordinary = new AtomicInteger();
        try (Gateway gateway = new Gateway(body -> body.contains("acting as a compaction engine") ? answer(SUMMARY)
                : ordinary.getAndIncrement() < 2 ? tool("probe", "{}") : answer("done"))) {
            HarnessEngine engine = engine(gateway, true, null);
            String saved = root.resolve("session").toString();
            CompactingFileAgentSession session = new CompactingFileAgentSession("memory-compact", saved);
            call(engine, session, memories, "alpha " + String.join("", Collections.nCopies(400, "original task context ")), false);
            assertNotNull(session.checkpoints().load(session.getMessages()));
            assertTrue(recalls(session).stream().anyMatch(m -> "restore".equals(m.getMetadataAs(MemoryContextInterceptor.ACTION))));
            int summary = -1;
            for (int i = 0; i < gateway.requests.size(); i++) {
                String request = gateway.requests.get(i);
                if (request.contains("acting as a compaction engine")) { if (summary < 0) summary = i; }
                else assertTrue(latestRecall(request).contains("ALPHA_RECALL_MARKER"));
            }
            assertTrue(summary > 0);
            String before = gateway.requests.get(summary - 1), summaryRequest = gateway.requests.get(summary);
            assertEquals(tools(before), tools(summaryRequest));
            List<ONode> a = ONode.ofJson(before).get("messages").getArray(), b = ONode.ofJson(summaryRequest).get("messages").getArray();
            for (int i = 0; i < b.size() - 1; i++) assertEquals(a.get(i).toJson(), b.get(i).toJson());
            CompactingFileAgentSession reopened = new CompactingFileAgentSession("memory-compact", saved);
            call(engine, reopened, memories, "beta", false);
            assertTrue(latestRecall(gateway.requests.get(gateway.requests.size() - 1)).contains("BETA_RECALL_MARKER"));
            memories.settings("alice", false, 1000);
            call(engine, reopened, memories, "continue", false);
            assertTrue(latestRecall(gateway.requests.get(gateway.requests.size() - 1)).contains("Memory is disabled"));
        }
    }

    @Test void projectedRecallIsRestoredFromTheCurrentSnapshot() throws Throwable {
        MemoryService memories = memories();
        ReActInterceptor rewrite = new ReActInterceptor() {
            @Override public void onReasonStart(ReActTrace trace, StringBuilder system) {
                List<ChatMessage> changed = new ArrayList<>();
                for (ChatMessage message : trace.getWorkingMemory().getMessages()) {
                    if (Boolean.TRUE.equals(message.getMetadataAs(MemoryContextInterceptor.MARKER))) {
                        ChatMessage clipped = ChatMessage.ofUser("clipped recall"); clipped.addMetadata(message.getMetadata()); changed.add(clipped);
                    } else changed.add(message);
                }
                trace.getWorkingMemory().replaceMessages(changed);
            }
        };
        try (Gateway gateway = new Gateway(body -> answer("done"))) {
            AgentSession session = InMemoryAgentSession.of("projected-recall"); HarnessEngine engine = engine(gateway, false, rewrite);
            call(engine, session, memories, "alpha", false);
            call(engine, session, memories, "alpha", false);
            assertEquals(2, recalls(session).size());
            assertEquals("restore", recalls(session).get(1).getMetadataAs(MemoryContextInterceptor.ACTION));
            assertTrue(latestRecall(gateway.requests.get(1)).contains("ALPHA_RECALL_MARKER"));
            assertFalse(latestRecall(gateway.requests.get(1)).contains("clipped recall"));
        }
    }

    @Test void ordinaryNetworkRetryKeepsTheExactRequestAndDoesNotDuplicateRecall() throws Throwable {
        MemoryService memories = memories();
        try (Gateway gateway = new Gateway(body -> answer("done"))) {
            gateway.failures.set(1);
            AgentSession session = InMemoryAgentSession.of("retry-recall");
            call(engine(gateway, false, null), session, memories, "alpha", false);
            assertEquals(2, gateway.requests.size());
            assertEquals(ONode.ofJson(gateway.requests.get(0)).toJson(), ONode.ofJson(gateway.requests.get(1)).toJson());
            assertEquals(1, recalls(session).size());
        }
    }

    @Test void childOnlyInheritsRecallAndHasNoMemoryTools() throws Throwable {
        MemoryService memories = memories(); AtomicInteger mainCalls = new AtomicInteger();
        try (Gateway gateway = new Gateway(body -> {
            if (body.contains("dispatch alpha") && mainCalls.getAndIncrement() == 0)
                return tool("task", "{\"agent_name\":\"worker\",\"description\":\"test\",\"prompt\":\"child-work\"}");
            return answer("done");
        })) {
            HarnessEngine engine = engine(gateway, false, null);
            engine.getAgentManager().addAgent(AgentDefinition.builder().name("worker").description("worker").systemPrompt("Child work").build());
            call(engine, InMemoryAgentSession.of("inherit"), memories, "dispatch alpha", false);
            assertEquals(3, gateway.requests.size());
            String child = gateway.requests.get(1);
            assertTrue(latestRecall(child).contains("ALPHA_RECALL_MARKER"));
            assertFalse(system(child).contains("ALPHA_RECALL_MARKER"));
            assertFalse(tools(child).contains("memory_save")); assertFalse(tools(child).contains("memory_search"));
        }
    }

    private MemoryService memories() {
        MemoryService service = new MemoryService(root.resolve("data")); service.settings("alice", true, 1000);
        for (String key : Arrays.asList("alpha", "beta")) {
            Map<String, Object> entry = new LinkedHashMap<>(); entry.put("scope", "project"); entry.put("key", key);
            entry.put("content", key.toUpperCase(Locale.ROOT) + "_RECALL_MARKER"); entry.put("pinned", false);
            service.save("alice", "ws-a", entry, "manual");
        }
        return service;
    }
    private HarnessEngine engine(Gateway gateway, boolean compact, ReActInterceptor rewrite) {
        ChatConfig config = new ChatConfig(); config.setName("local"); config.setModel("local"); config.setProvider("openai");
        config.setContextLength(compact ? 1000L : 128000L);
        config.setApiUrl("http://127.0.0.1:" + gateway.server.getAddress().getPort() + "/v1/chat/completions");
        HarnessEngine.Builder builder = HarnessEngine.of(root.toString(), ".soloncode").systemPrompt("Stable system")
                .sessionProvider(id -> sessions.computeIfAbsent(id, InMemoryAgentSession::of)).toolsAdd(Arrays.asList("read", "task", "multitask"))
                .subagentEnabled(true).modelAdd(config).defaultModel("local").modelRetries(2).maxTurns(8)
                .extensionAdd((owner, name, agent) -> {
                    new WorkspaceInstructionsInterceptor(root).install(agent);
                    agent.defaultToolAdd(new Probe());
                    if (rewrite != null) agent.defaultInterceptorAdd(1000, rewrite);
                    agent.defaultInterceptorAdd(2000, new MemoryContextInterceptor());
                    StableToolDefinitionsInterceptor.install(agent);
                });
        if (compact) builder.compressionInterceptor(new CacheAlignedCompressionInterceptor(10, .8, .01, 128));
        return builder.build();
    }
    private void call(HarnessEngine engine, AgentSession session, MemoryService memories, String prompt, boolean stream) throws Throwable {
        Map<String, Object> snapshot = memories.snapshot("alice", "ws-a", prompt);
        sessions.put(session.getSessionId(), session);
        session.getContext().put(MemoryService.SNAPSHOT_KEY, snapshot);
        if (stream) engine.prompt(prompt).session(session).options(options -> { options.retryConfig(2, 1); MemoryTalent.configure(options, memories, "alice", "ws-a", session.getSessionId(), snapshot); }).stream().blockLast();
        else engine.prompt(prompt).session(session).options(options -> { options.retryConfig(2, 1); MemoryTalent.configure(options, memories, "alice", "ws-a", session.getSessionId(), snapshot); }).call();
    }
    public static class Probe extends AbsToolProvider {
        @ToolMapping(name = "probe", description = "Return test evidence")
        public String probe() { return String.join("", Collections.nCopies(200, "test evidence ")); }
    }
    private List<ChatMessage> recalls(AgentSession session) {
        List<ChatMessage> messages = new ArrayList<>();
        for (ChatMessage message : session.getMessages()) if (Boolean.TRUE.equals(message.getMetadataAs(MemoryContextInterceptor.MARKER))) messages.add(message);
        return messages;
    }
    private static String system(String body) { return ONode.ofJson(body).get("messages").get(0).get("content").getString(); }
    private static String tools(String body) { return ONode.ofJson(body).get("tools").toJson(); }
    private static String latestRecall(String body) {
        String latest = "";
        for (ONode message : ONode.ofJson(body).get("messages").getArray()) {
            String content = message.get("content").getString();
            if ("user".equals(message.get("role").getString()) && content != null && content.startsWith("<system-reminder>\nMemory references")) latest = content;
        }
        return latest;
    }
    private static void assertPrefix(String first, String next) {
        List<ONode> a = ONode.ofJson(first).get("messages").getArray(), b = ONode.ofJson(next).get("messages").getArray();
        assertTrue(b.size() > a.size());
        for (int i = 0; i < a.size(); i++) assertEquals(a.get(i).toJson(), b.get(i).toJson());
    }
    private static String answer(String content) { return "{\"role\":\"assistant\",\"content\":" + ONode.serialize(content) + "}"; }
    private static String tool(String name, String args) { return "{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"test-call\",\"type\":\"function\",\"function\":{\"name\":" + ONode.serialize(name) + ",\"arguments\":" + ONode.serialize(args) + "}}]}"; }
    interface Reply { String respond(String body); }
    static class Gateway implements AutoCloseable {
        final HttpServer server; final List<String> requests = new ArrayList<>();
        final AtomicInteger failures = new AtomicInteger();
        Gateway(Reply reply) throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
                    while ((n = exchange.getRequestBody().read(buffer)) >= 0) output.write(buffer, 0, n);
                    String body = new String(output.toByteArray(), StandardCharsets.UTF_8); requests.add(body);
                    if (failures.getAndDecrement() > 0) {
                        byte[] error = "{\"error\":{\"message\":\"temporary outage\"}}".getBytes(StandardCharsets.UTF_8);
                        exchange.getResponseHeaders().set("Content-Type", "application/json");
                        exchange.sendResponseHeaders(503, error.length); exchange.getResponseBody().write(error); return;
                    }
                    String message = reply.respond(body), finish = message.contains("tool_calls") ? "tool_calls" : "stop";
                    boolean stream = ONode.ofJson(body).get("stream").getBoolean();
                    String payload = "{\"id\":\"test\",\"choices\":[{\"index\":0,\"" + (stream ? "delta" : "message")
                            + "\":" + message + ",\"finish_reason\":\"" + finish + "\"}]}";
                    byte[] bytes = (stream ? "data: " + payload + "\n\ndata: [DONE]\n\n" : payload).getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                } finally { exchange.close(); }
            });
            server.start();
        }
        @Override public void close() { server.stop(0); }
    }
}
