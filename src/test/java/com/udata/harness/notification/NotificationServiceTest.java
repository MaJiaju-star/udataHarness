package com.udata.harness.notification;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.noear.snack4.ONode;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.udata.harness.notification.NotificationRepository.rows;

class NotificationServiceTest {
    @TempDir Path root;
    HttpServer server; NotificationService service;
    AtomicInteger requests = new AtomicInteger(); AtomicReference<String> body = new AtomicReference<>(), auth = new AtomicReference<>(), notificationId = new AtomicReference<>(), method = new AtomicReference<>();
    @BeforeEach void setup() throws Exception {
        service = new NotificationService(root); server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/send", exchange -> {
            requests.incrementAndGet(); method.set(exchange.getRequestMethod()); auth.set(exchange.getRequestHeaders().getFirst("Authorization")); notificationId.set(exchange.getRequestHeaders().getFirst("X-Udata-Notification-Id"));
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[2048]; int count;
            while ((count = exchange.getRequestBody().read(buffer)) != -1) bytes.write(buffer, 0, count);
            body.set(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
            byte[] reply = "{\"errcode\":0,\"data\":{\"success\":true}}".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, reply.length); exchange.getResponseBody().write(reply); exchange.close();
        });
        server.createContext("/redirect", exchange -> { exchange.getResponseHeaders().set("Location", url("/send")); exchange.sendResponseHeaders(302, -1); exchange.close(); });
        server.createContext("/fail", exchange -> { exchange.sendResponseHeaders(500, -1); exchange.close(); }); server.start();
    }
    @AfterEach void cleanup() { service.close(); server.stop(0); }
    private String url(String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }
    private Map<String, Object> form() {
        Map<String, Object> config = new LinkedHashMap<>(); config.put("name", "内部消息"); config.put("enabled", true); config.put("url", url("/send?key=private-query-token"));
        config.put("method", "POST"); config.put("timeoutMs", 1000); config.put("bodyFormat", "json"); config.put("body", "{\"summary\":\"${result.summary}\",\"execution\":\"${execution.id}\"}");
        Map<String, Object> header = new LinkedHashMap<>(); header.put("name", "Authorization"); header.put("value", "Bearer private-auth-token");
        config.put("headers", Collections.singletonList(header)); config.put("statuses", Arrays.asList("succeeded", "failed")); config.put("successPath", "errcode"); config.put("successValue", "0"); return config;
    }
    private Map<String, Object> await(String id) throws Exception {
        long end = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < end) {
            for (Map<String, Object> record : rows(service.list("alice"), "records")) if (id.equals(record.get("id")) && !Arrays.asList("queued", "sending").contains(record.get("status"))) return record;
            Thread.sleep(10);
        }
        fail("Notification did not complete"); return null;
    }
    @Test void secretsIsolationPersistenceAndBlankEdits() throws Exception {
        String id = service.save("alice", form());
        String publicJson = ONode.serialize(service.list("alice")); assertFalse(publicJson.contains("private-auth-token")); assertFalse(publicJson.contains("private-query-token"));
        assertTrue(rows(service.list("bob"), "configs").isEmpty()); assertThrows(IllegalArgumentException.class, () -> service.remove("bob", id));
        Map<String, Object> edit = rows(service.list("alice"), "configs").get(0); edit.put("name", "新名称"); service.save("alice", edit);
        String delivery = service.test("alice", edit); assertEquals("succeeded", await(delivery).get("status")); assertEquals("Bearer private-auth-token", auth.get());
        service.close(); service = new NotificationService(root); assertEquals("新名称", rows(service.list("alice"), "configs").get(0).get("name"));
        assertTrue(com.udata.harness.repository.ModelConfigurationFiles.isProtected(root.resolve("users/alice/notifications.json")));
        assertThrows(IllegalArgumentException.class, () -> service.list("../alice"));
    }
    @Test void automaticDispatchIsAsynchronousDeduplicatedAndManualResendIsExplicit() throws Exception {
        service.save("alice", form()); Map<String, Object> variables = NotificationTemplate.sample(); variables.put("result.summary", "quoted\"\ntext");
        service.dispatch("alice", variables); service.dispatch("alice", variables);
        List<Map<String, Object>> records = rows(service.list("alice"), "records"); assertEquals(1, records.size());
        String id = (String) records.get(0).get("id"); assertEquals("succeeded", await(id).get("status")); assertEquals(1, requests.get()); assertEquals(id, notificationId.get());
        assertEquals("quoted\"\ntext", ONode.ofJson(body.get()).get("summary").getString());
        String retry = service.resend("alice", id); assertEquals("succeeded", await(retry).get("status")); assertNotEquals(id, retry); assertEquals(2, requests.get());
        variables.put("execution.id", "stopped"); variables.put("execution.status", "cancelled"); service.dispatch("alice", variables); assertEquals(2, rows(service.list("alice"), "records").size());
    }
    @Test void redirectsErrorsAndBusinessFailuresAreRecordedWithoutResponseOrCredentials() throws Exception {
        for (String path : Arrays.asList("/redirect", "/fail", "/send")) {
            Map<String, Object> config = form(); config.put("url", url(path)); config.put("successValue", "1");
            String id = service.test("alice", config); Map<String, Object> record = await(id); assertEquals("failed", record.get("status"));
            String json = ONode.serialize(record); assertFalse(json.contains("private-auth-token")); assertFalse(json.contains("errcode"));
        }
        assertEquals(1, requests.get()); // the redirect never reached /send
    }
    @Test void missingSuccessFieldCannotPassAsNull() throws Exception {
        Map<String, Object> config = form(); config.put("successPath", "missing"); config.put("successValue", "null");
        assertEquals("failed", await(service.test("alice", config)).get("status"));
    }
    @Test void disabledAndInvalidConfigurationsNeverSend() throws Exception {
        String id = service.save("alice", form()); service.enabled("alice", id, false); service.dispatch("alice", NotificationTemplate.sample());
        assertTrue(rows(service.list("alice"), "records").isEmpty());
        for (String bad : Arrays.asList("file:///secret", "http://user:password@localhost/x", "https://${unknown}/send")) {
            Map<String, Object> config = form(); config.put("url", bad); assertThrows(IllegalArgumentException.class, () -> service.save("alice", config));
        }
        Map<String, Object> invalid = form(); invalid.put("body", "{\"x\":\"${unknown}\"}"); assertThrows(IllegalArgumentException.class, () -> service.save("alice", invalid));
        assertEquals(0, requests.get()); assertEquals(1, rows(service.list("alice"), "configs").size());
    }
    @Test void getPutAndDeleteRenderTextAndRespectBodySemantics() throws Exception {
        for (String requestMethod : Arrays.asList("GET", "PUT", "DELETE")) {
            Map<String, Object> config = form(); config.put("method", requestMethod); config.put("bodyFormat", "text"); config.put("body", "prefix ${session.title}");
            assertEquals("succeeded", await(service.test("alice", config)).get("status"));
            assertEquals(requestMethod, method.get());
            assertEquals("GET".equals(requestMethod) ? "" : "prefix 测试通知", body.get());
        }
    }
    @Test void restartMarksUnconfirmedDeliveriesInterruptedWithoutReplaying() {
        service.save("alice", form()); NotificationRepository repository = new NotificationRepository(root); Map<String, Object> state = repository.read("alice");
        Map<String, Object> record = new LinkedHashMap<>(); record.put("id", "old"); record.put("status", "sending"); rows(state, "records").add(record); repository.write("alice", state);
        service.close(); service = new NotificationService(root);
        assertEquals("interrupted", rows(service.list("alice"), "records").get(0).get("status")); assertEquals(0, requests.get());
    }
}
