package com.udata.harness.common.support;

import com.sun.net.httpserver.HttpServer;
import org.noear.snack4.ONode;
import org.noear.solon.ai.chat.ChatConfig;
import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Local wire-level fixture; no external API or credentials. */
final class CacheTestGateway implements AutoCloseable {
    interface Reply { Response respond(String body) throws Exception; }
    static final class Response {
        final int status; final String text; final boolean raw;
        Response(int status, String text, boolean raw) { this.status = status; this.text = text; this.raw = raw; }
    }
    final HttpServer server;
    final List<String> requests = new ArrayList<>();
    CacheTestGateway(Reply reply) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                ByteArrayOutputStream output = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
                while ((n = exchange.getRequestBody().read(buffer)) >= 0) output.write(buffer, 0, n);
                String body = new String(output.toByteArray(), StandardCharsets.UTF_8); requests.add(body);
                Response response = reply.respond(body);
                boolean stream = ONode.ofJson(body).get("stream").getBoolean() && response.status == 200;
                String payload = response.text;
                if (response.status == 200 && !response.raw) {
                    payload = "{\"id\":\"test\",\"choices\":[{\"index\":0,\"" + (stream ? "delta" : "message")
                            + "\":" + payload + ",\"finish_reason\":\"" + (payload.contains("tool_calls") ? "tool_calls" : "stop") + "\"}]}";
                    if (stream) payload = "data: " + payload + "\n\ndata: [DONE]\n\n";
                }
                byte[] bytes = payload.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", stream ? "text/event-stream" : "application/json");
                exchange.sendResponseHeaders(response.status, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (Exception error) { throw new RuntimeException(error); }
            finally { exchange.close(); }
        });
        server.start();
    }
    ChatConfig config() {
        ChatConfig config = new ChatConfig(); config.setName("local"); config.setModel("local"); config.setProvider("openai");
        config.setContextLength(128000L);
        config.setApiUrl("http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
        return config;
    }
    static Response answer(String text) { return new Response(200, "{\"role\":\"assistant\",\"content\":" + ONode.serialize(text) + "}", false); }
    static Response error(int code, String text) { return new Response(code, "{\"error\":{\"message\":" + ONode.serialize(text) + "}}", true); }
    static String system(String body) { return ONode.ofJson(body).get("messages").get(0).get("content").getString(); }
    static String tools(String body) { return ONode.ofJson(body).get("tools").toJson(); }
    static String catalog(String body, String kind) {
        String latest = "";
        for (ONode message : ONode.ofJson(body).get("messages").getArray()) {
            String content = message.get("content").getString();
            if ("user".equals(message.get("role").getString()) && content != null
                    && content.startsWith("<system-reminder>\nCapability catalog: " + kind + " ")) latest = content;
        }
        return latest;
    }
    @Override public void close() { server.stop(0); }
}
