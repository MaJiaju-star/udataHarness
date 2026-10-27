package com.udata.harness.common.support;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.harness.agent.AgentDefinition;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorkspaceInstructionsInterceptorTest {
    @TempDir Path directory;

    @Test void prefersClaudeAndFallsBackOnEmptyInvalidOrNonFileSource() throws Exception {
        write("AGENTS.md", "agent-rule"); write("CLAUDE.md", "\uFEFFclaude-rule");
        WorkspaceInstructionsInterceptor loader = new WorkspaceInstructionsInterceptor(directory);
        assertTrue(prompt(loader).contains("claude-rule"));
        assertFalse(prompt(loader).contains("agent-rule"));
        write("CLAUDE.md", " \n\t"); assertTrue(prompt(loader).contains("agent-rule"));
        Files.write(directory.resolve("CLAUDE.md"), new byte[]{(byte) 0xC3, (byte) 0x28});
        assertTrue(prompt(loader).contains("agent-rule"));
        Files.delete(directory.resolve("CLAUDE.md")); Files.createDirectory(directory.resolve("CLAUDE.md"));
        assertTrue(prompt(loader).contains("agent-rule"));
    }

    @Test void rereadsUpdatesDeletesAndKeepsWorkspaceBoundaries() throws Exception {
        WorkspaceInstructionsInterceptor loader = new WorkspaceInstructionsInterceptor(directory);
        assertEquals("base", prompt(loader));
        write("CLAUDE.md", "rule-one"); assertTrue(prompt(loader).contains("rule-one"));
        write("CLAUDE.md", "rule-two"); assertTrue(prompt(loader).contains("rule-two"));
        assertFalse(prompt(loader).contains("rule-one"));
        Path other = Files.createDirectory(directory.resolve("other"));
        assertEquals("base", prompt(new WorkspaceInstructionsInterceptor(other)));
        write("AGENTS.md", "fallback-rule"); Files.delete(directory.resolve("CLAUDE.md"));
        assertTrue(prompt(loader).contains("fallback-rule"));
        Files.delete(directory.resolve("AGENTS.md"));
        assertEquals("base", prompt(loader));
    }

    @Test void boundsContentAndMarksTruncationWithoutFallingBack() throws Exception {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < WorkspaceInstructionsInterceptor.MAX_CHARS + 100; i++) text.append('字');
        write("CLAUDE.md", text.toString()); write("AGENTS.md", "do-not-merge");
        String injected = prompt(new WorkspaceInstructionsInterceptor(directory));
        assertTrue(injected.contains("truncated to 10000 characters"));
        assertFalse(injected.contains("do-not-merge"));
        assertEquals(10000, injected.chars().filter(c -> c == '字').count());
    }

    @Test void selectedModeIsRereadAndManualModesNeverFallBack() throws Exception {
        write("CLAUDE.md", "claude-only-rule"); write("AGENTS.md", "agents-only-rule");
        java.util.concurrent.atomic.AtomicReference<String> mode = new java.util.concurrent.atomic.AtomicReference<>("auto");
        WorkspaceInstructionsInterceptor loader = new WorkspaceInstructionsInterceptor(directory, mode::get);
        assertTrue(prompt(loader).contains("claude-only-rule"));
        mode.set("agents"); assertTrue(prompt(loader).contains("agents-only-rule"));
        assertFalse(prompt(loader).contains("claude-only-rule"));
        Files.delete(directory.resolve("AGENTS.md")); assertEquals("base", prompt(loader));
        mode.set("claude"); assertTrue(prompt(loader).contains("claude-only-rule"));
        mode.set("off"); assertEquals("base", prompt(loader));
        mode.set("auto"); assertTrue(prompt(loader).contains("claude-only-rule"));
    }

    @Test void actualModelRequestsUseLatestRulesAndRemoveDeletedOnesWithoutRebuildingEngine() throws Throwable {
        List<String> systems = new ArrayList<>();
        HttpServer server = server((body) -> {
            systems.add(system(body)); return answer("done");
        });
        try {
            HarnessEngine engine = engine(server);
            Path sessionDirectory = directory.resolve("saved-session");
            org.noear.solon.ai.agent.AgentSession session = new com.udata.harness.repository.CompactingFileAgentSession(
                    "live-rules", sessionDirectory.toString());
            write("CLAUDE.md", "CLAUDE_VERSION_ONE"); write("AGENTS.md", "AGENT_FALLBACK");
            engine.prompt("first").session(session).call();
            write("CLAUDE.md", "CLAUDE_VERSION_TWO");
            engine.prompt("second").session(session).call();
            Files.delete(directory.resolve("CLAUDE.md"));
            java.util.List<org.noear.solon.ai.agent.AgentEvent> stream = engine.prompt("third").session(session).stream().collectList().block();
            WorkspaceInstructionsEvent statusEvent = (WorkspaceInstructionsEvent) stream.stream()
                    .filter(event -> event instanceof WorkspaceInstructionsEvent).findFirst().get();
            String json = com.udata.harness.common.util.StreamEventMapper.map(statusEvent);
            ONode status = ONode.ofJson(json);
            assertEquals("project_instructions", status.get("type").getString());
            assertEquals("AGENTS.md", status.get("source").getString());
            assertTrue(status.get("loaded").getBoolean());
            assertFalse(json.contains("AGENT_FALLBACK"), "Status must not include instruction contents");
            org.noear.solon.ai.agent.AgentSession restored = new com.udata.harness.repository.CompactingFileAgentSession(
                    "live-rules", sessionDirectory.toString());
            java.util.Map<?, ?> savedStatus = restored.getContext().getAs(WorkspaceInstructionsInterceptor.STATUS_KEY);
            assertEquals("AGENTS.md", savedStatus.get("source"));
            assertEquals(true, savedStatus.get("loaded"));
            Files.delete(directory.resolve("AGENTS.md"));
            engine.prompt("fourth").session(session).call();
            java.util.Map<?, ?> cleared = session.getContext().getAs(WorkspaceInstructionsInterceptor.STATUS_KEY);
            assertEquals(false, cleared.get("loaded"));
            assertNull(cleared.get("source"));
            assertEquals(4, systems.size());
            assertTrue(systems.get(0).contains("CLAUDE_VERSION_ONE"));
            assertFalse(systems.get(0).contains("AGENT_FALLBACK"));
            assertTrue(systems.get(1).contains("CLAUDE_VERSION_TWO"));
            assertFalse(systems.get(1).contains("CLAUDE_VERSION_ONE"));
            assertTrue(systems.get(2).contains("AGENT_FALLBACK"));
            assertFalse(systems.get(2).contains("CLAUDE_VERSION_TWO"));
            assertFalse(systems.get(3).contains("workspace-instructions"));
            assertTrue(systems.stream().allMatch(s -> s.contains("BASE_SYSTEM")));
        } finally { server.stop(0); }
    }

    @Test void childAndLaterReasoningReadUpdatedRulesInsideOneUserTurn() throws Throwable {
        AtomicInteger step = new AtomicInteger(); List<String> systems = new ArrayList<>();
        HttpServer server = server(body -> {
            systems.add(system(body));
            if (step.getAndIncrement() == 0) {
                write("CLAUDE.md", "UPDATED_FOR_CHILD_AND_PARENT");
                String args = "{\"agent_name\":\"worker\",\"description\":\"test\",\"prompt\":\"child-work\"}";
                return "{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"dispatch\",\"type\":\"function\",\"function\":{\"name\":\"task\",\"arguments\":" + ONode.serialize(args) + "}}]}";
            }
            return answer("done");
        });
        try {
            write("CLAUDE.md", "INITIAL_PARENT_RULE");
            HarnessEngine engine = engine(server);
            engine.getAgentManager().addAgent(AgentDefinition.builder().name("worker").description("Test worker").systemPrompt("BASE_SYSTEM child").build());
            engine.prompt("dispatch child").session(InMemoryAgentSession.of("parent-session")).call();
            assertEquals(3, systems.size());
            assertTrue(systems.get(0).contains("INITIAL_PARENT_RULE"));
            for (int i = 1; i < systems.size(); i++) {
                assertTrue(systems.get(i).contains("UPDATED_FOR_CHILD_AND_PARENT"));
                assertFalse(systems.get(i).contains("INITIAL_PARENT_RULE"));
            }
        } finally { server.stop(0); }
    }

    private HarnessEngine engine(HttpServer server) {
        return engine(server, () -> "auto");
    }

    @Test void persistedSelectionChangesActualModelRequestsWithoutRebuildingEngine() throws Throwable {
        java.util.List<String> systems = new java.util.ArrayList<>();
        HttpServer server = server(body -> { systems.add(system(body)); return answer("done"); });
        try {
            com.udata.harness.repository.ProjectInstructionSettings settings = new com.udata.harness.repository.ProjectInstructionSettings(directory.resolve("config"));
            HarnessEngine engine = engine(server, () -> settings.mode("alice", "workspace"));
            InMemoryAgentSession session = InMemoryAgentSession.of("selected-rules");
            write("CLAUDE.md", "CLAUDE_SELECTED"); write("AGENTS.md", "AGENTS_SELECTED");
            engine.prompt("auto").session(session).call();
            settings.save("alice", "workspace", "agents"); engine.prompt("agents").session(session).call();
            settings.save("alice", "workspace", "off"); engine.prompt("off").session(session).stream().blockLast();
            settings.save("alice", "workspace", "agents"); Files.delete(directory.resolve("AGENTS.md"));
            engine.prompt("unavailable").session(session).call();
            assertTrue(systems.get(0).contains("CLAUDE_SELECTED"));
            assertTrue(systems.get(1).contains("AGENTS_SELECTED")); assertFalse(systems.get(1).contains("CLAUDE_SELECTED"));
            assertFalse(systems.get(2).contains("workspace-instructions"));
            assertFalse(systems.get(3).contains("workspace-instructions"));
            java.util.Map<?, ?> status = session.getContext().getAs(WorkspaceInstructionsInterceptor.STATUS_KEY);
            assertEquals("agents", status.get("mode")); assertEquals(false, status.get("loaded"));
        } finally { server.stop(0); }
    }

    private HarnessEngine engine(HttpServer server, java.util.function.Supplier<String> mode) {
        ChatConfig config = new ChatConfig(); config.setName("stub"); config.setModel("stub"); config.setProvider("openai");
        config.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        return HarnessEngine.of(directory.toString(), ".soloncode").systemPrompt("BASE_SYSTEM")
                .subagentEnabled(true).toolsAdd(java.util.Arrays.asList("task", "multitask"))
                .sessionProvider(InMemoryAgentSession::of).modelAdd(config).defaultModel("stub").modelRetries(1).maxTurns(5)
                .extensionAdd((owner, name, builder) -> builder.defaultInterceptorAdd(Integer.MIN_VALUE + 1,
                        new WorkspaceInstructionsInterceptor(directory, mode))).build();
    }

    private interface Responder { String reply(String body) throws Exception; }
    private HttpServer server(Responder responder) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                byte[] buffer = new byte[4096]; int count;
                while ((count = exchange.getRequestBody().read(buffer)) >= 0) bytes.write(buffer, 0, count);
                String body = new String(bytes.toByteArray(), StandardCharsets.UTF_8);
                String message = responder.reply(body);
                boolean stream = ONode.ofJson(body).get("stream").getBoolean();
                String payload = "{\"id\":\"stub\",\"choices\":[{\"index\":0,\"" + (stream ? "delta" : "message") + "\":" + message
                        + ",\"finish_reason\":\"" + (message.contains("tool_calls") ? "tool_calls" : "stop") + "\"}]}";
                byte[] response = (stream ? "data: " + payload + "\n\ndata: [DONE]\n\n" : payload).getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } catch (Exception e) { exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        });
        server.start(); return server;
    }

    private static String answer(String text) { return "{\"role\":\"assistant\",\"content\":" + ONode.serialize(text) + "}"; }
    private static String system(String body) {
        StringBuilder result = new StringBuilder();
        for (ONode message : ONode.ofJson(body).get("messages").getArray())
            if ("system".equals(message.get("role").getString())) result.append(message.get("content").getString());
        return result.toString();
    }
    private void write(String name, String content) throws Exception { Files.write(directory.resolve(name), content.getBytes(StandardCharsets.UTF_8)); }
    private String prompt(WorkspaceInstructionsInterceptor loader) {
        StringBuilder system = new StringBuilder("base"); loader.onReasonStart(null, system); return system.toString();
    }
}
