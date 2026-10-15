package com.udata.harness.common.context;

import com.sun.net.httpserver.HttpServer;
import com.udata.harness.repository.CompactingFileAgentSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.chat.tool.AbsToolProvider;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.annotation.Param;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CacheAlignedCompressionInterceptorTest {
    @TempDir Path temporary;

    @Test
    void replaysThePreviousRequestPrefixAndAppendsOneSummaryInstruction() throws Throwable {
        verifyPrefixAndRestart(false);
    }

    @Test
    void streamRequestsAlsoReuseThePrefix() throws Throwable {
        verifyPrefixAndRestart(true);
    }

    private void verifyPrefixAndRestart(boolean streaming) throws Throwable {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> requests = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger ordinary = new AtomicInteger();
        AtomicBoolean verifiedPrefix = new AtomicBoolean();
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                String body = read(exchange.getRequestBody());
                requests.add(body);
                boolean summary = body.contains("acting as a compaction engine");
                int turn = summary ? -1 : ordinary.getAndIncrement();
                String message = summary
                        ? "{\"role\":\"assistant\",\"content\":\"## Primary Request and Intent\\n- Keep the task.\"}"
                        : turn < 2
                        ? "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call-" + turn
                            + "\",\"type\":\"function\",\"function\":{\"name\":\"grep\",\"arguments\":\"{\\\"pattern\\\":\\\"x\\\"}\"}}]}"
                        : "{\"role\":\"assistant\",\"content\":\"done\"}";
                String finish = turn >= 0 && turn < 2 ? "tool_calls" : "stop";
                boolean isStream = body.contains("\"stream\":true");
                String payload;
                if (isStream) {
                    String delta = turn < 2 ? message.replace("\"content\":null,", "") : message;
                    payload = "data: {\"id\":\"stub\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,"
                            + "\"delta\":" + delta + ",\"finish_reason\":null}]}\n\n"
                            + "data: {\"id\":\"stub\",\"object\":\"chat.completion.chunk\",\"choices\":[{\"index\":0,"
                            + "\"delta\":{},\"finish_reason\":\"" + finish + "\"}]}\n\n"
                            + "data: [DONE]\n\n";
                } else {
                    payload = "{\"id\":\"stub\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,"
                            + "\"message\":" + message + ",\"finish_reason\":\"" + finish
                            + "\"}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":10,\"total_tokens\":110}}";
                }
                byte[] response = payload.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", isStream ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            ChatConfig config = new ChatConfig();
            config.setName("stub");
            config.setModel("stub");
            config.setProvider("openai");
            config.setContextLength(800L);
            config.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
            HarnessEngine engine = HarnessEngine.of(temporary.toString(), ".soloncode")
                    .sessionProvider(InMemoryAgentSession::of)
                    .systemPrompt("Complete the task with tools.")
                    .compressionInterceptor(new CacheAlignedCompressionInterceptor(10, 0.8, 0.01, 64,
                            (sessionId, model, before, after, aligned, usage) -> verifiedPrefix.set(aligned)))
                    .modelAdd(config).defaultModel("stub").modelRetries(1).maxTurns(6)
                    .extensionAdd((owner, name, builder) -> builder.defaultToolAdd(new SearchFixture()))
                    .build();
            String sessionDirectory = temporary.resolve("session").toString();
            CompactingFileAgentSession session = new CompactingFileAgentSession("cache-test", sessionDirectory);
            String prompt = "Analyze " + String.join("", Collections.nCopies(280, "abcdefghij"));
            if (streaming) engine.prompt(prompt).session(session).stream().collectList().block();
            else engine.prompt(prompt).session(session).call();

            int summaryIndex = -1;
            for (int i = 0; i < requests.size(); i++) {
                if (requests.get(i).contains("acting as a compaction engine")) summaryIndex = i;
            }
            assertTrue(summaryIndex > 1, "Expected a summary after two ordinary reasoning calls");
            ONode previous = ONode.ofJson(requests.get(summaryIndex - 1));
            ONode summary = ONode.ofJson(requests.get(summaryIndex));
            List<ONode> previousMessages = previous.get("messages").getArray();
            List<ONode> summaryMessages = summary.get("messages").getArray();
            assertEquals(previous.get("model").getString(), summary.get("model").getString());
            assertEquals(previous.get("tools").toJson(), summary.get("tools").toJson());
            for (int i = 0; i < summaryMessages.size() - 1; i++) {
                assertEquals(previousMessages.get(i).toJson(), summaryMessages.get(i).toJson());
            }
            assertEquals("user", summaryMessages.get(summaryMessages.size() - 1).get("role").getString());
            assertTrue(verifiedPrefix.get(), "Expected final provider JSON to retain the previous prefix");
            assertTrue(requests.get(summaryIndex + 1).contains("<compacted-summary>"));
            CompactingFileAgentSession reopened = new CompactingFileAgentSession("cache-test", sessionDirectory);
            assertEquals(2, reopened.getMessages().size());
            assertEquals(2, reopened.getLatestMessages(200).size());
            assertTrue(reopened.getLatestMessages(200).get(0).getContent().contains("<compacted-summary>"));
            assertEquals("done", reopened.getLatestMessages(200).get(1).getContent());
            if (streaming) engine.prompt("follow-up").session(reopened).stream().collectList().block();
            else engine.prompt("follow-up").session(reopened).call();
            String nextTurn = requests.get(requests.size() - 1);
            assertTrue(nextTurn.contains("<compacted-summary>"));
            assertTrue(nextTurn.contains("follow-up"));
            assertTrue(!nextTurn.contains("abcdefghijabcdefghijabcdefghij"));
        } finally {
            server.stop(0);
        }
    }

    private static String read(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int size;
        while ((size = input.read(buffer)) >= 0) output.write(buffer, 0, size);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }

    public static final class SearchFixture extends AbsToolProvider {
        @ToolMapping(name = "grep", description = "Find fixture text")
        public String grep(@Param("pattern") String pattern) {
            return String.join("", Collections.nCopies(20, "search-result "));
        }
    }
}
