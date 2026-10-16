package org.noear.solon.ai.talents.cli;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.harness.HarnessEngine;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用本地模型协议桩验证真实 Harness 的隐藏参数注入和批次版本冻结。
 */
class SessionFileVersionsIntegrationTest {
    @TempDir Path workspace;

    @Test
    void automaticVersionsSurviveRealToolBatchesWithoutModelHashArguments() throws Throwable {
        Path source = workspace.resolve("source.txt");
        Files.write(source, "original".getBytes(StandardCharsets.UTF_8));
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);

        //1. 模型先读取，再在一个批次里读取外部改动并尝试旧计划的覆盖。
        server.createContext("/", exchange -> {
            try {
                String body = read(exchange.getRequestBody());
                assertFalse(body.contains("expected_sha256"));
                assertFalse(body.contains("__sessionId"));
                assertFalse(body.contains("__fileVersionBatch"));
                assertFalse(body.contains("SHA-256"));
                assertFalse(body.contains("sourceHash"));
                assertFalse(body.contains("editDiff"));
                assertFalse(body.contains("--- a/source.txt"));
                assertFalse(body.contains(FileVersion.sha256("original".getBytes(StandardCharsets.UTF_8))));
                assertFalse(body.contains(FileVersion.sha256("external".getBytes(StandardCharsets.UTF_8))));
                int step = requests.getAndIncrement();
                String calls;
                if (step == 0) {
                    calls = call("read", "{\"file_path\":\"source.txt\"}");
                } else if (step == 1) {
                    assertTrue(body.contains("original"));
                    Files.write(source, "external".getBytes(StandardCharsets.UTF_8));
                    calls = call("read", "{\"file_path\":\"source.txt\"}") + ","
                            + call("write", "{\"file_path\":\"source.txt\",\"content\":\"blind overwrite\"}");
                } else if (step == 2) {
                    assertTrue(body.contains("版本冲突"));
                    assertEquals("external", new String(Files.readAllBytes(source), StandardCharsets.UTF_8));
                    calls = call("write", "{\"file_path\":\"source.txt\",\"content\":\"accepted\"}");
                } else if (step == 3) {
                    assertEquals("accepted", new String(Files.readAllBytes(source), StandardCharsets.UTF_8));
                    calls = call("edit", "{\"file_path\":\"source.txt\",\"edits\":["
                            + "{\"old_str\":\"accepted\",\"new_str\":\"next\"}]}");
                } else {
                    assertEquals("next", new String(Files.readAllBytes(source), StandardCharsets.UTF_8));
                    calls = null;
                }
                String message = calls == null ? "{\"role\":\"assistant\",\"content\":\"done\"}"
                        : "{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[" + calls + "]}";
                byte[] response = ("{\"id\":\"response-" + step + "\",\"object\":\"chat.completion\","
                        + "\"choices\":[{\"index\":0,\"message\":" + message
                        + ",\"finish_reason\":\"" + (calls == null ? "stop" : "tool_calls") + "\"}]}")
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
            //2. 与生产环境一样，将同一份版本记录注入读取、修改工具和批次拦截器。
            SessionFileVersions versions = new SessionFileVersions();
            ChatConfig config = new ChatConfig();
            config.setName("local-stub");
            config.setModel("local-stub");
            config.setProvider("openai");
            config.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
            config.setContextLength(128000L);
            HarnessEngine engine = HarnessEngine.of(workspace.toString(), ".soloncode")
                    .sessionProvider(InMemoryAgentSession::of)
                    .toolsAdd(Arrays.asList("read", "write", "edit"))
                    .modelAdd(config).defaultModel("local-stub").modelRetries(1).maxTurns(8)
                    .extensionAdd((owner, agentName, builder) -> {
                        builder.modelOptions(options -> {
                            options.talentAdd(new BoundedReadTool(owner.getTerminalTalent(),
                                    owner.getWorkspace(), null, versions));
                            options.talentAdd(new SafeMutationTalent(owner.getTerminalTalent(),
                                    owner.getWorkspace(), true, true, versions));
                        });
                        builder.defaultInterceptorAdd(-1000, versions);
                    }).build();
            try {
                engine.prompt("Read and update the file.").session(InMemoryAgentSession.of("session-a")).call();
            } catch (Throwable e) {
                if (failure.get() != null) throw new AssertionError("Local model assertion failed", failure.get());
                throw e;
            }
            assertNull(failure.get());
            assertEquals(5, requests.get());
        } finally {
            server.stop(0);
        }
    }

    /**
     * 生成模型返回的工具调用，参数中不包含文件摘要或隐藏上下文。
     *
     * @param name 工具名称
     * @param arguments 参数 JSON
     * @return 协议工具调用 JSON
     */
    private static String call(String name, String arguments) {
        return "{\"id\":\"" + java.util.UUID.randomUUID() + "\",\"type\":\"function\",\"function\":{"
                + "\"name\":\"" + name + "\",\"arguments\":" + ONode.serialize(arguments) + "}}";
    }

    /**
     * 读取本地协议桩的请求正文。
     *
     * @param input 请求流
     * @return UTF-8 文本
     * @throws java.io.IOException 读取失败
     */
    private static String read(InputStream input) throws java.io.IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) != -1) output.write(buffer, 0, count);
        return new String(output.toByteArray(), StandardCharsets.UTF_8);
    }
}
