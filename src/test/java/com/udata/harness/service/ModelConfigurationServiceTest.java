package com.udata.harness.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.core.Props;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ModelConfigurationServiceTest {
    @TempDir Path root;
    private ModelConfigurationService service(Props props) {
        return new ModelConfigurationService(root, props);
    }
    private Map<String, Object> form() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("label", "开发模型"); input.put("model", "test-model"); input.put("provider", "openai");
        input.put("apiUrl", "http://localhost:8089/v1/chat/completions"); input.put("apiKey", "fake-test-key");
        input.put("contextLength", 128000); input.put("enabled", true); return input;
    }
    @SuppressWarnings("unchecked") private List<Map<String, Object>> rows(ModelConfigurationService service, String user) {
        return (List<Map<String, Object>>) service.list(user).get("models");
    }
    @Test void restartIsolationAndSecretPreservation() throws Exception {
        ModelConfigurationService first = service(new Props());
        Map<String, Object> input = form(); String id = first.save("alice", input);
        ModelConfigurationService reopened = service(new Props());
        assertEquals("fake-test-key", reopened.effective("alice").get(0).getApiKey());
        assertTrue(reopened.effective("bob").isEmpty());
        String json = org.noear.snack4.ONode.serialize(reopened.list("alice"));
        assertFalse(json.contains("fake-test-key")); assertFalse(json.contains("secretRef"));
        assertTrue(new String(Files.readAllBytes(root.resolve("users/alice/models.json")), StandardCharsets.UTF_8).contains("fake-test-key"));
        assertFalse(Files.exists(root.resolve("model-secrets")));
        assertFalse(Files.exists(root.resolve("external/master.key")));
        input.put("id", id); input.put("apiKey", ""); input.put("label", "重命名"); reopened.save("alice", input);
        assertEquals(id, reopened.effective("alice").get(0).getNameOrModel());
        assertEquals("fake-test-key", reopened.effective("alice").get(0).getApiKey());
        input.put("clearApiKey", true); reopened.save("alice", input);
        assertEquals("", reopened.effective("alice").get(0).getApiKey());
    }
    @Test void systemPresetsAreInheritedReadOnlyAndLegacyAliasesAreStable() {
        Props props = new Props(); props.put("agent.model.model", "actual-model");
        props.put("agent.model.default", "friendly-alias"); props.put("agent.model.api-key", "system-secret");
        ModelConfigurationService service = service(props);
        assertEquals("friendly-alias", service.effective("alice").get(0).getNameOrModel());
        assertFalse(org.noear.snack4.ONode.serialize(service.list("alice")).contains("system-secret"));
        Map<String, Object> input = form(); input.put("id", "friendly-alias");
        assertThrows(IllegalArgumentException.class, () -> service.save("alice", input));
        assertThrows(IllegalArgumentException.class, () -> service.remove("alice", "friendly-alias"));
    }
    @Test void invalidConfigDoesNotChangeStoredRevisionAndDefaultsFollowEnabledModels() {
        ModelConfigurationService service = service(new Props()); Map<String, Object> input = form();
        String id = service.save("alice", input);
        service.defaults("alice", new LinkedHashMap<String, Object>() {{put("default", id); put("super", id);}});
        assertEquals(id, service.preferred("alice", "super"));
        String revision = service.revision("alice"); input.put("apiUrl", "https://user:password@example.com?key=secret");
        assertThrows(IllegalArgumentException.class, () -> service.save("alice", input));
        assertEquals(revision, service.revision("alice"));
        input.put("apiUrl", "http://localhost:8089"); input.put("id", id); input.put("enabled", false); input.put("apiKey", "");
        service.save("alice", input); assertTrue(service.effective("alice").isEmpty());
        assertEquals("", service.preferred("alice", "default"));
        assertTrue(((Map<?, ?>) service.list("alice").get("defaults")).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> service.defaults("alice", Collections.singletonMap("default", id)));
        assertThrows(IllegalArgumentException.class, () -> service.list("../alice"));
        assertThrows(IllegalArgumentException.class, () -> service.save("bob", input));
    }
    @Test void corruptFilesFailClosedAndLegacyKeysCanBeReplaced() throws Exception {
        ModelConfigurationService service = service(new Props());
        String id = service.save("alice", form());
        Path file = root.resolve("users/alice/models.json");
        String stored = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        Files.write(file, stored.replace("\"apiKey\":\"fake-test-key\"", "\"secretRef\":\"legacy-reference\"").getBytes(StandardCharsets.UTF_8));
        assertEquals(true, rows(service, "alice").get(0).get("apiKeyNeedsReset"));
        assertEquals(false, rows(service, "alice").get(0).get("hasApiKey"));
        assertTrue(service.effective("alice").isEmpty());
        Map<String, Object> input = form(); input.put("id", id); input.put("apiKey", "");
        assertThrows(IllegalArgumentException.class, () -> service.save("alice", input));
        input.put("apiKey", "replacement-test-key"); service.save("alice", input);
        assertEquals("replacement-test-key", service.effective("alice").get(0).getApiKey());
        assertFalse(new String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains("secretRef"));
        Files.write(file, "invalid-json".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> service.list("alice"));
    }
    @Test void personalCopyReusesCredentialsWithoutExposingOrMutatingTheSource() {
        ModelConfigurationService service = service(new Props());
        String original = service.save("alice", form());
        Map<String, Object> copy = form(); copy.put("apiKey", ""); copy.put("copyFromId", original);
        copy.put("label", "另一个模型"); copy.put("model", "second-model");
        String copied = service.save("alice", copy);
        assertNotEquals(original, copied);
        assertEquals(2, service.effective("alice").size());
        assertTrue(service.effective("alice").stream().allMatch(model -> "fake-test-key".equals(model.getApiKey())));
        assertThrows(IllegalArgumentException.class, () -> service.save("bob", copy));
        Map<String, Object> changed = form(); changed.put("id", original); changed.put("apiKey", "rotated-test-key");
        service.save("alice", changed);
        assertEquals("fake-test-key", service.effective("alice").stream().filter(model -> copied.equals(model.getNameOrModel()))
                .findFirst().get().getApiKey());
    }
    @Test void fileToolsCannotReadOrSearchModelSecrets() throws Exception {
        ModelConfigurationService service = service(new Props()); service.save("alice", form());
        org.noear.solon.ai.talents.cli.TerminalTalent terminal = new org.noear.solon.ai.talents.cli.TerminalTalent(
                new org.noear.solon.ai.talents.mount.MountManager(root.toString()));
        org.noear.solon.ai.talents.cli.BoundedReadTool reader = new org.noear.solon.ai.talents.cli.BoundedReadTool(terminal, root.toString());
        assertThrows(SecurityException.class, () -> reader.read("users/alice/models.json", 1, 1, null));
        org.noear.solon.ai.talents.cli.BoundedGrepTool grep = new org.noear.solon.ai.talents.cli.BoundedGrepTool(terminal, root.toString());
        assertThrows(SecurityException.class, () -> grep.grep(".*", "users/alice/models.json", null, "content", null, null, null, null, null));
    }

    @Test void testConnectionUsesDraftAndRetainedKeyWithoutSavingIt() throws Exception {
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        List<String> auth = new ArrayList<>();
        List<String> gatewaySessions = new ArrayList<>();
        server.createContext("/v1/chat/completions", exchange -> {
            auth.add(exchange.getRequestHeaders().getFirst("Authorization"));
            gatewaySessions.add(exchange.getRequestHeaders().getFirst("session-id"));
            byte[] response = ("{\"id\":\"test\",\"object\":\"chat.completion\",\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"OK\"},\"finish_reason\":\"stop\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            ModelConfigurationService service = service(new Props()); Map<String, Object> input = form();
            input.put("apiUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions");
            String id = service.save("alice", input); String revision = service.revision("alice");
            input.put("id", id); input.put("apiKey", "");
            assertEquals(true, service.testConnection("alice", input).get("success"));
            assertEquals("Bearer fake-test-key", auth.get(0));
            assertTrue(gatewaySessions.get(0).matches("test-[a-f0-9-]{36}"));
            assertEquals(true, service.testConnection("alice", input).get("success"));
            assertNotEquals(gatewaySessions.get(0), gatewaySessions.get(1));
            assertEquals(revision, service.revision("alice"));
        } finally { server.stop(0); }
    }
}
