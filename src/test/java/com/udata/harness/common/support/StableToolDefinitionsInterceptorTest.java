package com.udata.harness.common.support;

import org.junit.jupiter.api.Test;
import org.noear.solon.ai.chat.ChatOptions;
import org.noear.solon.ai.chat.tool.FunctionTool;
import org.noear.solon.ai.chat.tool.FunctionToolDesc;
import java.util.*;
import java.util.stream.Collectors;
import static org.junit.jupiter.api.Assertions.*;

class StableToolDefinitionsInterceptorTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @Test void sortsFinalDeclarationsWithoutChangingTheWinningToolOrItsSchema() {
        ChatOptions options = new ChatOptions();
        FunctionToolDesc original = new FunctionToolDesc("read").description("original");
        FunctionToolDesc winner = new FunctionToolDesc("read").description("safe replacement")
                .inputSchema("{\"type\":\"object\",\"properties\":{\"file_path\":{\"type\":\"string\"}}}").strict(false);
        winner.metaPut("talent", "SafeRead");
        options.toolAdd(new FunctionToolDesc("zebra"), original, new FunctionToolDesc("alpha"));
        options.toolAdd(winner);
        StableToolDefinitionsInterceptor.normalize(options);
        assertEquals(Arrays.asList("alpha", "read", "zebra"), options.tools().stream().map(FunctionTool::name).collect(Collectors.toList()));
        assertSame(winner, options.tool("read"));
        assertEquals("SafeRead", options.tool("read").meta().get("talent"));
        assertEquals(winner.inputSchema(), options.tool("read").inputSchema());
        assertEquals(Boolean.FALSE, options.tool("read").strict());
        assertEquals("safe replacement", options.tool("read").description());
    }

    @Test void independentRequestCopiesDoNotMutateTheSharedRegistrationOrder() {
        ChatOptions shared = new ChatOptions();
        shared.toolAdd(new FunctionToolDesc("zebra"), new FunctionToolDesc("alpha"));
        ChatOptions one = new ChatOptions(); one.putAll(shared);
        ChatOptions two = new ChatOptions(); two.putAll(shared);
        StableToolDefinitionsInterceptor.normalize(one);
        assertEquals("zebra", shared.tools().iterator().next().name());
        assertEquals("zebra", two.tools().iterator().next().name());
        assertSame(shared.tool("alpha"), one.tool("alpha"));
        assertEquals("alpha", one.tools().iterator().next().name());
    }

    @Test void realTalentActivationStillExecutesTheLaterReadOverrideInSyncAndStream() throws Throwable {
        for (boolean stream : Arrays.asList(false, true)) {
            List<String> requests = new ArrayList<>();
            com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    java.io.ByteArrayOutputStream output = new java.io.ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
                    while ((n = exchange.getRequestBody().read(buffer)) >= 0) output.write(buffer, 0, n);
                    String body = new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8); requests.add(body);
                    boolean first = requests.size() == 1;
                    String message = first
                            ? "{\"role\":\"assistant\",\"tool_calls\":[{\"id\":\"read-test\",\"type\":\"function\",\"function\":{\"name\":\"read\",\"arguments\":\"{}\"}}]}"
                            : "{\"role\":\"assistant\",\"content\":\"done\"}";
                    String payload = "{\"id\":\"test\",\"choices\":[{\"index\":0,\"" + (stream ? "delta" : "message")
                            + "\":" + message + ",\"finish_reason\":\"" + (first ? "tool_calls" : "stop") + "\"}]}";
                    byte[] response = (stream ? "data: " + payload + "\n\ndata: [DONE]\n\n" : payload).getBytes(java.nio.charset.StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
                    exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
                } finally { exchange.close(); }
            });
            server.start();
            try {
                org.noear.solon.ai.chat.ChatConfig config = new org.noear.solon.ai.chat.ChatConfig();
                config.setName("local"); config.setModel("local"); config.setProvider("openai");
                config.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
                org.noear.solon.ai.harness.HarnessEngine engine = org.noear.solon.ai.harness.HarnessEngine.of(directory.toString(), ".soloncode")
                        .systemPrompt("Test override").toolsAdd(Collections.emptyList())
                        .sessionProvider(org.noear.solon.ai.agent.session.InMemoryAgentSession::of)
                        .modelAdd(config).defaultModel("local").modelRetries(1).maxTurns(3)
                        .extensionAdd((owner, name, builder) -> {
                            builder.defaultTalentAdd(new OriginalRead());
                            builder.defaultTalentAdd(new WinningRead());
                            StableToolDefinitionsInterceptor.install(builder);
                        }).build();
                org.noear.solon.ai.agent.AgentSession session = org.noear.solon.ai.agent.session.InMemoryAgentSession.of("override-" + stream);
                if (stream) engine.prompt("read").session(session).stream().blockLast(); else engine.prompt("read").session(session).call();
                assertEquals(2, requests.size());
                org.noear.snack4.ONode body = org.noear.snack4.ONode.ofJson(requests.get(0));
                List<String> names = new ArrayList<>();
                for (org.noear.snack4.ONode item : body.get("tools").getArray()) {
                    names.add(item.get("function").get("name").getString());
                    if ("read".equals(item.get("function").get("name").getString()))
                        assertTrue(item.get("function").get("description").getString().contains("winning read"));
                }
                List<String> sorted = new ArrayList<>(names); Collections.sort(sorted); assertEquals(sorted, names);
                assertEquals(1, Collections.frequency(names, "read"));
                assertTrue(requests.get(1).contains("WINNING_SAFE_READ"));
                assertFalse(requests.get(1).contains("ORIGINAL_READ_EXECUTED"));
            } finally { server.stop(0); }
        }
    }

    public static class OriginalRead extends org.noear.solon.ai.chat.talent.AbsTalent {
        @org.noear.solon.ai.annotation.ToolMapping(name = "read", description = "original read")
        public String read() { return "ORIGINAL_READ_EXECUTED"; }
    }
    public static class WinningRead extends org.noear.solon.ai.chat.talent.AbsTalent {
        @org.noear.solon.ai.annotation.ToolMapping(name = "read", description = "winning read")
        public String read() { return "WINNING_SAFE_READ"; }
    }
}
