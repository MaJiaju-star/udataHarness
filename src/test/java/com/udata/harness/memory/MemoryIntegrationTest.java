package com.udata.harness.memory;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.snack4.ONode;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.net.InetSocketAddress;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryIntegrationTest {
    @TempDir Path root;
    @Test void sameSessionDropsOldMemoryToolsAndInstructionsWhenTurnSettingsChange() throws Throwable {
        List<String> requests = new ArrayList<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(); byte[] bytes = new byte[4096]; int count;
            while ((count = exchange.getRequestBody().read(bytes)) >= 0) buffer.write(bytes, 0, count);
            requests.add(new String(buffer.toByteArray(), StandardCharsets.UTF_8));
            byte[] reply = "{\"id\":\"memory-test\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"done\"},\"finish_reason\":\"stop\"}]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, reply.length);
            exchange.getResponseBody().write(reply); exchange.close();
        });
        server.start();
        try {
            ChatConfig config = new ChatConfig(); config.setName("local"); config.setModel("local"); config.setProvider("openai");
            config.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
            HarnessEngine engine = HarnessEngine.of(root.toString(), ".soloncode").toolsAdd(Collections.singletonList("read"))
                    .sessionProvider(InMemoryAgentSession::of)
                    .modelAdd(config).defaultModel("local").modelRetries(1).maxTurns(2).build();
            MemoryService memories = new MemoryService(root.resolve("data"));
            Map<String, Object> entry = new LinkedHashMap<>(); entry.put("scope", "project"); entry.put("key", "marker"); entry.put("content", "MEMORY_INJECTION_MARKER"); entry.put("pinned", true);
            memories.save("alice", "ws-a", entry, "manual");
            InMemoryAgentSession session = InMemoryAgentSession.of("memory-session");
            for (int i = 0; i < 3; i++) {
                memories.settings("alice", i != 1, 1000);
                String prompt = i == 0 ? "记住这个项目的约定" : "继续检查";
                Map<String, Object> snapshot = memories.snapshot("alice", "ws-a", prompt);
                engine.prompt(prompt).session(session).options(options -> MemoryTalent.configure(options, memories, "alice", "ws-a", session.getSessionId(), snapshot)).call();
            }
            assertEquals(3, requests.size());
            String firstTools = ONode.ofJson(requests.get(0)).get("tools").toJson();
            assertTrue(firstTools.contains("memory_save"));
            String disabledTools = ONode.ofJson(requests.get(1)).get("tools").toJson();
            assertFalse(disabledTools.contains("memory_"));
            String disabledSystem = ONode.ofJson(requests.get(1)).get("messages").get(0).get("content").getString();
            assertFalse(disabledSystem.contains("MEMORY_INJECTION_MARKER"));
            String nextTools = ONode.ofJson(requests.get(2)).get("tools").toJson();
            assertTrue(nextTools.contains("memory_search")); assertFalse(nextTools.contains("memory_save"));
            assertTrue(ONode.ofJson(requests.get(2)).get("messages").get(0).get("content").getString().contains("MEMORY_INJECTION_MARKER"));
        } finally { server.stop(0); }
    }
}
