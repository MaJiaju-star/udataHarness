package com.udata.harness.memory;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.agent.session.InMemoryAgentSession;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.ai.agent.react.intercept.compress.CompressionUtil;
import org.noear.snack4.ONode;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class MemoryServiceTest {
    @TempDir Path root;
    private Map<String, Object> form(String scope, String key, String value) {
        Map<String, Object> input = new LinkedHashMap<>(); input.put("scope", scope); input.put("key", key); input.put("content", value); input.put("pinned", true); return input;
    }
    private List<Map<String, Object>> entries(Map<String, Object> state) { return MemoryService.rows(state, "entries"); }
    @Test void defaultOffRetainsManualRecordsAndEnablingRecallsAcrossSessions() {
        MemoryService service = new MemoryService(root);
        service.save("alice", "ws-a", form("project", "java", "本项目使用 Java 8"), "manual");
        assertEquals(false, service.list("alice", "ws-a").get("enabled"));
        assertTrue(entries(service.snapshot("alice", "ws-a", "Java" )).isEmpty());
        service.settings("alice", true, 1000);
        assertEquals(1, entries(service.snapshot("alice", "ws-a", "继续开发")).size());
        service.settings("alice", false, 1000);
        assertTrue(entries(service.snapshot("alice", "ws-a", "Java")).isEmpty());
        assertEquals(1, entries(service.list("alice", "ws-a")).size());
        assertEquals(false, new MemoryService(root).list("alice", "ws-a").get("enabled"));
    }
    @Test void globalSharedAcrossProjectsButNeverUsersAndProjectOverridesSameKey() {
        MemoryService service = new MemoryService(root); service.settings("alice", true, 2000);
        service.save("alice", "ws-a", form("global", "language", "默认英语"), "manual");
        service.save("alice", "ws-a", form("project", "language", "本项目使用中文"), "manual");
        service.save("alice", "ws-b", form("project", "build", "另一个项目使用 Gradle"), "manual");
        String a = ONode.serialize(entries(service.snapshot("alice", "ws-a", "language")));
        assertTrue(a.contains("中文")); assertFalse(a.contains("英语")); assertFalse(a.contains("Gradle"));
        assertEquals(2, entries(service.list("alice", "ws-b")).size());
        assertTrue(entries(service.list("bob", "ws-a")).isEmpty());
    }
    @Test void revisionConflictDoesNotOverwriteAndSoftDeleteRestores() {
        MemoryService service = new MemoryService(root);
        Map<String, Object> saved = service.save("alice", "ws-a", form("project", "build", "mvn test"), "manual");
        Map<String, Object> updated = new LinkedHashMap<>(saved); updated.put("content", "mvn verify");
        updated = service.save("alice", "ws-a", updated, "manual");
        assertThrows(IllegalArgumentException.class, () -> service.save("alice", "ws-a", saved, "manual"));
        assertThrows(IllegalArgumentException.class, () -> service.save("alice", "ws-b", saved, "manual"));
        service.state("alice", "ws-a", MemoryService.text(updated.get("id")), 2, "deleted");
        service.settings("alice", true, 1000); assertTrue(entries(service.snapshot("alice", "ws-a", "build")).isEmpty());
        service.state("alice", "ws-a", MemoryService.text(updated.get("id")), 3, "active");
        assertEquals(1, entries(service.snapshot("alice", "ws-a", "build")).size());
        assertTrue(ONode.serialize(service.list("alice", "ws-a").get("changes")).contains("mvn test"));
    }
    @Test void chineseRetrievalIsBoundedAndOnlyUsesActiveRelevantOrPinnedItems() {
        MemoryService service = new MemoryService(root); service.settings("alice", true, 1000);
        Map<String, Object> match = form("project", "token-accounting", "缓存命中率保留，Token 仅统计主 Agent"); match.put("pinned", false);
        service.save("alice", "ws-a", match, "manual");
        Map<String, Object> unrelated = form("project", "database", "数据库使用 MySQL"); unrelated.put("pinned", false);
        service.save("alice", "ws-a", unrelated, "manual");
        Map<String, Object> recalled = service.snapshot("alice", "ws-a", "缓存命中率应该怎么展示？");
        assertEquals(1, entries(recalled).size());
        assertTrue(CompressionUtil.countTokens((String) recalled.get("instruction")) <= 1000);
        service.settings("alice", true, 256);
        StringBuilder huge = new StringBuilder(); for (int i = 0; i < 1900; i++) huge.append('长');
        service.save("alice", "ws-a", form("global", "large", huge.toString()), "manual");
        assertTrue(CompressionUtil.countTokens((String) service.snapshot("alice", "ws-a", "large").get("instruction")) <= 256);
    }
    @Test void toolsRequireExplicitIntentAndChildrenAreReadOnlyAndSnapshotIsStable() {
        MemoryService service = new MemoryService(root); service.settings("alice", true, 1000);
        Map<String, Object> ordinary = service.snapshot("alice", "ws-a", "请查看当前项目");
        MemoryTalent read = new MemoryTalent(service, "alice", "ws-a", "session-a", ordinary);
        assertEquals(1, read.getTools(Prompt.of("test")).size());
        assertTrue(read.save("a", "bad", "global", null, null, false).contains("未获得"));
        Map<String, Object> explicit = service.snapshot("alice", "ws-a", "请记住这个项目使用 Java 8");
        MemoryTalent writer = new MemoryTalent(service, "alice", "ws-a", "session-a", explicit);
        assertTrue(writer.save("java", "Java 8", "global", null, null, false).contains("全局记忆需要"));
        service.settings("alice", false, 1000);
        assertTrue(writer.save("java", "Java 8", "project", null, null, true).contains("Java 8")); // Current turn keeps its settings.
        assertTrue(entries(ordinary).isEmpty());
        InMemoryAgentSession parent = InMemoryAgentSession.of("parent"); parent.getContext().put(MemoryService.SNAPSHOT_KEY, explicit);
        MemoryTalent child = MemoryTalent.inherited(parent); assertNotNull(child); assertTrue(child.getTools(null).isEmpty());
        assertTrue(child.save("bad", "bad", "project", null, null, false).contains("未获得"));
    }
    @Test void concurrentUpdatesRejectStaleRevisionInsteadOfLosingChanges() throws Exception {
        MemoryService service = new MemoryService(root);
        Map<String, Object> original = service.save("alice", "ws-a", form("project", "build", "old"), "manual");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Callable<Boolean> update = () -> { try { service.save("alice", "ws-a", new LinkedHashMap<>(original), "manual"); return true; } catch (IllegalArgumentException e) { return false; } };
            List<Future<Boolean>> results = pool.invokeAll(Arrays.asList(update, update));
            int successes = 0; for (Future<Boolean> f : results) if (f.get()) successes++;
            assertEquals(1, successes); assertEquals(2, ((Number) entries(service.list("alice", "ws-a")).get(0).get("revision")).intValue());
        } finally { pool.shutdownNow(); }
    }
    @Test void corruptFileIsNeverSilentlyReplacedAndFilesProtectedAcrossRestart() throws Exception {
        MemoryRepository repo = new MemoryRepository(root); Path file = repo.file("alice"); Files.createDirectories(file.getParent());
        Files.write(file, "{broken".getBytes(StandardCharsets.UTF_8));
        MemoryService service = new MemoryService(root);
        assertTrue(com.udata.harness.repository.ModelConfigurationFiles.isProtected(file));
        assertThrows(IllegalStateException.class, () -> service.settings("alice", true, 1000));
        assertEquals("{broken", new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> service.list("../alice", "ws-a"));
        assertThrows(IllegalArgumentException.class, () -> service.list("alice", "../../ws-a"));
    }
    @Test void scopeMovePersistsAndDuplicatesMustBeUpdatedWithRevision() {
        MemoryService service = new MemoryService(root);
        Map<String, Object> saved = service.save("alice", "ws-a", form("project", "language", "中文"), "manual");
        assertThrows(IllegalArgumentException.class, () -> service.save("alice", "ws-a", form("project", "language", "英语"), "manual"));
        Map<String, Object> moved = new LinkedHashMap<>(saved); moved.put("scope", "global");
        service.save("alice", "ws-a", moved, "manual");
        assertEquals(1, entries(new MemoryService(root).list("alice", "ws-b")).size());
    }
}
