package com.udata.harness.common.context;

import com.sun.net.httpserver.HttpServer;
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
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.udata.harness.common.context.ContextArtifactStoreTest.repeat;
import static org.junit.jupiter.api.Assertions.*;

/** Real Harness/ActionTask/tool injection with a loopback-only model protocol stub. */
class ContextManagementIntegrationTest {
    @TempDir Path directory;

    @Test
    void archivesAndRestoresThroughRealHarnessWithoutAModelAccount() throws Throwable {
        runScenario(1, 2000, 20000);
    }

    @Test
    void enforcesBatchBudgetBeforeNextRequestThroughRealHarness() throws Throwable {
        runScenario(2, 20000, 11000);
    }

    @Test
    void switchesTasksSearchesAndRestoresArchivedHistoryThroughRealHarness() throws Throwable {
        String original = repeat("code line\n", 600) + "MIDDLE_EVIDENCE" + repeat("tail\n", 600);
        AtomicInteger sequence = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<String> recoveredId = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                String body = read(exchange.getRequestBody());
                int step = sequence.getAndIncrement();
                assertFalse(body.contains("\"__sessionId\""));
                String name;
                String args;
                if (step == 0) {
                    name = "context_task_update";
                    args = "{\"task_id\":\"A\",\"state\":\"active\",\"requirements\":\"Keep public API\"}";
                } else if (step == 1) {
                    assertTrue(toolContents(body).stream().anyMatch(text -> text.contains("task_id=A")));
                    name = "grep"; args = "{\"pattern\":\"test\"}";
                } else if (step == 2) {
                    assertTrue(toolContents(body).contains(original));
                    name = "context_task_update"; args = "{\"task_id\":\"B\",\"state\":\"active\"}";
                } else if (step == 3) {
                    assertFalse(toolContents(body).contains(original));
                    assertTrue(toolContents(body).stream().anyMatch(text -> text.contains("task_paused")));
                    name = "context_search"; args = "{\"query\":\"MIDDLE_EVIDENCE\",\"task_id\":\"A\"}";
                } else if (step == 4) {
                    String search = toolContents(body).stream().filter(text -> text.startsWith("Literal,")).findFirst().get();
                    Matcher match = Pattern.compile("artifact_id=([a-f0-9]{64})").matcher(search);
                    assertTrue(match.find()); recoveredId.set(match.group(1));
                    name = "context_restore";
                    args = "{\"artifact_id\":\"" + recoveredId.get() + "\",\"offset\":"
                            + original.indexOf("MIDDLE_EVIDENCE") + ",\"limit\":32}";
                } else if (step == 5) {
                    assertTrue(toolContents(body).stream().anyMatch(text -> text.startsWith("Historical") && text.contains("MIDDLE_EVIDENCE")));
                    name = "context_task_snapshot"; args = "{\"task_id\":\"A\"}";
                } else {
                    assertTrue(toolContents(body).stream().anyMatch(text -> text.contains("state: paused") && text.contains("Keep public API")));
                    name = null; args = null;
                }
                String message = name == null ? "{\"role\":\"assistant\",\"content\":\"done\"}"
                        : "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[" + toolCall("call-" + step, name, args) + "]}";
                byte[] response = ("{\"id\":\"response-" + step + "\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":"
                        + message + ",\"finish_reason\":\"" + (name == null ? "stop" : "tool_calls") + "\"}]}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (Throwable e) {
                failure.compareAndSet(null, e); exchange.sendResponseHeaders(500, -1);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            ContextArtifactStore store = new ContextArtifactStore(directory.resolve("artifacts"));
            ContextTaskStore tasks = new ContextTaskStore(store);
            ContextManagementOptions limits = new ContextManagementOptions(20000, 40000, 20, 1000);
            ChatConfig config = new ChatConfig();
            config.setName("local-stub"); config.setModel("local-stub"); config.setProvider("openai");
            config.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
            config.setContextLength(128000L);
            HarnessEngine engine = HarnessEngine.of(directory.resolve("workspace").toString(), ".soloncode")
                    .sessionProvider(InMemoryAgentSession::of).systemPrompt("Use tools, then answer done.")
                    .modelAdd(config).defaultModel("local-stub").modelRetries(1).maxTurns(8)
                    .extensionAdd((owner, agentName, builder) -> {
                        builder.defaultToolAdd(new SearchFixture(original));
                        builder.defaultToolAdd(new ContextMemoryTool(store, tasks));
                        builder.defaultToolAdd(new ContextRestoreTool(store, 1000));
                        builder.defaultInterceptorAdd(1000, new ToolResultContextInterceptor(store, limits, tasks, directory));
                    }).build();
            try {
                engine.prompt("Fix A, switch to B, recover A evidence and constraints.")
                        .session(InMemoryAgentSession.of("lifecycle-session")).call();
            } catch (Throwable e) {
                if (failure.get() != null) throw new AssertionError("Loopback model assertion failed", failure.get());
                throw e;
            }
            assertNull(failure.get()); assertEquals(7, sequence.get());
            assertEquals("B", tasks.currentTaskId("lifecycle-session"));
            assertFalse(store.search("other-session", "MIDDLE_EVIDENCE", null, null, 0, 5).contains(recoveredId.get()));
        } finally { server.stop(0); }
    }

    private void runScenario(int tools, int singleLimit, int batchLimit) throws Throwable {
        String original = repeat("log line\n", 600) + "MIDDLE_EVIDENCE" + repeat("tail\n", 600);
        List<String> requests = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger sequence = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                String body = read(exchange.getRequestBody());
                requests.add(body);
                int step = sequence.getAndIncrement();
                String message;
                String finish;
                if (step == 0) {
                    StringBuilder calls = new StringBuilder();
                    for (int i = 0; i < tools; i++) {
                        if (i > 0) calls.append(',');
                        calls.append(toolCall("call-" + i, "grep", "{\"pattern\":\"test\"}"));
                    }
                    message = "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[" + calls + "]}";
                    finish = "tool_calls";
                } else if (step == 1) {
                    List<String> contents = toolContents(body);
                    String projection = contents.stream().filter(value -> value.startsWith("<context-artifact"))
                            .findFirst().orElseThrow(() -> new AssertionError("No archived tool result"));
                    if (tools > 1) assertTrue(contents.stream().mapToInt(String::length).sum() <= batchLimit);
                    else assertFalse(projection.contains("MIDDLE_EVIDENCE"));
                    Matcher match = Pattern.compile("id=\"([a-f0-9]{64})\"").matcher(projection);
                    assertTrue(match.find());
                    String args = "{\"artifact_id\":\"" + match.group(1) + "\",\"offset\":"
                            + original.indexOf("MIDDLE_EVIDENCE") + ",\"limit\":32}";
                    message = "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":["
                            + toolCall("restore-call", "context_restore", args) + "]}";
                    finish = "tool_calls";
                } else {
                    List<String> contents = toolContents(body);
                    assertTrue(contents.stream().anyMatch(value -> value.startsWith("Historical tool-result snapshot")
                            && value.contains("MIDDLE_EVIDENCE")));
                    message = "{\"role\":\"assistant\",\"content\":\"done\"}";
                    finish = "stop";
                }
                byte[] response = ("{\"id\":\"response-" + step + "\",\"object\":\"chat.completion\","
                        + "\"choices\":[{\"index\":0,\"message\":" + message + ",\"finish_reason\":\"" + finish
                        + "\"}],\"usage\":{\"prompt_tokens\":100,\"completion_tokens\":10,\"total_tokens\":110}}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } catch (Throwable e) {
                failure.compareAndSet(null, e);
                exchange.sendResponseHeaders(500, -1);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            ContextArtifactStore store = new ContextArtifactStore(directory.resolve("artifacts"));
            ContextManagementOptions limits = new ContextManagementOptions(singleLimit, batchLimit, 20, 1000);
            ChatConfig config = new ChatConfig();
            config.setName("local-stub");
            config.setModel("local-stub");
            config.setProvider("openai");
            config.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
            config.setContextLength(128000L);
            HarnessEngine engine = HarnessEngine.of(directory.resolve("workspace").toString(), ".soloncode")
                    .sessionProvider(InMemoryAgentSession::of)
                    .systemPrompt("Use tools, then answer done.")
                    .modelAdd(config).defaultModel("local-stub").modelRetries(1).maxTurns(5)
                    .extensionAdd((owner, agentName, builder) -> {
                        builder.defaultToolAdd(new SearchFixture(original));
                        builder.defaultToolAdd(new ContextRestoreTool(store, limits.restoreMaxChars));
                        builder.defaultInterceptorAdd(1000, new ToolResultContextInterceptor(store, limits));
                    }).build();
            try {
                engine.prompt("Run the search and recover the evidence.")
                        .session(InMemoryAgentSession.of("integration-session")).call();
            } catch (Throwable e) {
                if (failure.get() != null) throw new AssertionError("Loopback model assertion failed", failure.get());
                throw e;
            }
            assertNull(failure.get());
            assertEquals(3, requests.size());
            assertFalse(requests.get(0).contains("__sessionId"));
        } finally {
            server.stop(0);
        }
    }

    private static List<String> toolContents(String request) {
        List<String> result = new ArrayList<>();
        for (ONode message : ONode.ofJson(request).get("messages").getArray()) {
            if ("tool".equals(message.get("role").getString())) result.add(message.get("content").getString());
        }
        return result;
    }

    private static String toolCall(String id, String name, String arguments) {
        return "{\"id\":\"" + id + "\",\"type\":\"function\",\"function\":{\"name\":\""
                + name + "\",\"arguments\":" + ONode.serialize(arguments) + "}}";
    }

    private static String read(InputStream in) throws java.io.IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = in.read(buffer)) >= 0) bytes.write(buffer, 0, count);
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }

    public static class SearchFixture extends AbsToolProvider {
        private final String output;
        public SearchFixture(String output) { this.output = output; }
        @ToolMapping(name = "grep", description = "Local deterministic search fixture")
        public String grep(@Param(value = "pattern") String pattern) { return output; }
    }
}
