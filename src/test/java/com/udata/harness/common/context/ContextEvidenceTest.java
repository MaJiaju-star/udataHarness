package com.udata.harness.common.context;

import com.udata.harness.repository.ModelContextAgentSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.AiUsage;
import org.noear.solon.ai.chat.message.*;
import org.noear.solon.ai.chat.tool.ToolResult;

import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ContextEvidenceTest {
    @TempDir Path directory;

    @Test void versionsRangesAndCanonicalArgumentOrderIdentifyEvidence() {
        ContextArtifactStore store = new ContextArtifactStore(directory);
        Map<String, Object> first = new LinkedHashMap<>();
        first.put("path", "A.java"); first.put("offset", 10);
        Map<String, Object> reordered = new LinkedHashMap<>();
        reordered.put("offset", 10); reordered.put("path", "A.java");
        String id = store.put("read", first, "class A {}");
        assertEquals(id, store.put("read", reordered, "class A {}"));
        assertNotEquals(id, store.put("read", first, "class A { int n; }"));
        reordered.put("offset", 20);
        assertNotEquals(id, store.put("read", reordered, "class A {}"));
        assertEquals("class", store.restore(id, 0, 5).get("content"));
        assertEquals(5, store.restore(id, 0, 5).get("nextOffset"));
        assertTrue((Boolean) store.restore(id, 5, 100).get("historicalEvidence"));
    }

    @Test void searchRestoresMiddleErrorsAndRejectsPathsOutsideSession() {
        ContextArtifactStore store = new ContextArtifactStore(directory);
        String text = repeat("log line\n", 500) + "ERROR: MissingSymbol in A.java\n" + repeat("log line\n", 500);
        String id = store.put("bash", Collections.singletonMap("command", "test A"), text);
        List<Map<String, Object>> hits = store.search("MissingSymbol A.java", "bash", 5);
        assertEquals(1, hits.size());
        assertEquals(id, hits.get(0).get("id"));
        assertTrue(String.valueOf(hits.get(0).get("preview")).contains("MissingSymbol"));
        assertTrue(store.search("MissingSymbol", "read", 5).isEmpty());
        assertThrows(IllegalArgumentException.class, () -> store.restore("../secret", 0, 100));
        assertThrows(IllegalArgumentException.class, () -> store.restore(id, 0, 20000));
        assertThrows(IllegalArgumentException.class, () -> store.search("", null, 5));
        assertTrue(new ContextArtifactStore(directory.resolve("other-session")).search("MissingSymbol", null, 5).isEmpty());
    }

    @Test void corruptionIsDetectedAndFailedWriteDoesNotProduceProjection() throws Exception {
        ContextArtifactStore store = new ContextArtifactStore(directory);
        String id = store.put("read", Collections.emptyMap(), "version1");
        Path file = directory.resolve("artifacts").resolve(id + ".json");
        String json = new String(Files.readAllBytes(file), java.nio.charset.StandardCharsets.UTF_8);
        Files.write(file, json.replace("version1", "version2").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> store.restore(id, 0, 100));
        Path blocked = directory.resolve("blocked");
        Files.createDirectories(blocked);
        Files.write(blocked.resolve("artifacts"), new byte[]{1});
        ToolMessage original = tool("read", repeat("x", 5000));
        assertThrows(IllegalStateException.class, () -> new ToolResultPolicy(1000).project(original,
                Collections.emptyMap(), Collections.singletonList(original), new ContextArtifactStore(blocked)));
        assertFalse(original.hasMetadata(ToolResultPolicy.PROJECTION));
        assertEquals(5000, original.getContent().length());
    }

    @Test void dedupRequiresFullEvidenceStillInActiveContext() {
        ToolResultPolicy policy = new ToolResultPolicy(1000);
        ContextArtifactStore store = new ContextArtifactStore(directory);
        Map<String, Object> args = Collections.singletonMap("path", "A.java");
        String content = "class A {} " + repeat("source ", 100);
        ToolMessage first = policy.project(tool("read", content), args, new ArrayList<>(), store);
        ToolMessage repeated = policy.project(tool("read", content), args, Collections.singletonList(first), store);
        assertEquals("full", first.getMetadataAs(ToolResultPolicy.PROJECTION));
        assertEquals("reference", repeated.getMetadataAs(ToolResultPolicy.PROJECTION));
        assertEquals("full", policy.project(tool("read", content), args,
                Collections.singletonList(repeated), store).getMetadataAs(ToolResultPolicy.PROJECTION));
        assertEquals("full", policy.project(tool("read", "class A { int n; }"), args,
                Collections.singletonList(first), store).getMetadataAs(ToolResultPolicy.PROJECTION));
        ToolMessage tiny = policy.project(tool("read", "tiny"), args, Collections.emptyList(), store);
        assertEquals("tiny", policy.project(tool("read", "tiny"), args, Collections.singletonList(tiny), store).getContent());
        ToolMessage large = policy.project(tool("read", repeat("x", 5000)), args, Collections.emptyList(), store);
        assertEquals("excerpt", policy.project(tool("read", repeat("x", 5000)), args,
                Collections.singletonList(large), store).getMetadataAs(ToolResultPolicy.PROJECTION));
    }

    @Test void largeLogKeepsDiagnosticEvidenceAndOriginalToolPairIdentity() {
        String text = repeat("normal\n", 500) + "ERROR: critical middle failure\n" + repeat("normal\n", 500);
        ToolMessage original = tool("bash", text);
        ToolMessage projected = new ToolResultPolicy(1000).project(original, Collections.emptyMap(),
                Collections.singletonList(original), new ContextArtifactStore(directory));
        assertTrue(projected.getContent().length() <= 1000);
        assertTrue(projected.getContent().contains("critical middle failure"));
        assertEquals(original.getToolCallId(), projected.getToolCallId());
        assertEquals(text, original.getContent());
        assertTrue(projected.getContent().contains("Partial tool output"));
        String singleLine = repeat("x", 3000) + "ERROR single-line failure" + repeat("y", 3000);
        assertTrue(new ToolResultPolicy(1000).project(tool("bash", singleLine), Collections.emptyMap(), Collections.emptyList(),
                new ContextArtifactStore(directory)).getContent().contains("single-line failure"));
        ToolMessage chart = tool("render_echart", text);
        assertSame(chart, new ToolResultPolicy(1000).project(chart, Collections.emptyMap(), Collections.singletonList(chart), new ContextArtifactStore(directory)));
    }

    @Test void crowdedSuccessLinesDoNotDisplaceFailureAndSearchKeepsWholeMatches() {
        ContextArtifactStore artifacts = new ContextArtifactStore(directory);
        ToolResultPolicy policy = new ToolResultPolicy(1000);
        String log = repeat("setup\n", 300) + repeat("test passed\n", 200)
                + "ERROR critical final failure\n" + repeat("tail\n", 500);
        ToolMessage projected = policy.project(tool("bash", log), Collections.emptyMap(), Collections.emptyList(), artifacts);
        assertTrue(projected.getContent().contains("critical final failure"));
        assertEquals("diagnostics", projected.getMetadataAs(ToolResultPolicy.POLICY));
        String matches = repeat("src/A.java:123:exact matching source\n", 100);
        ToolMessage search = policy.project(tool("grep", matches), Collections.emptyMap(), Collections.emptyList(), artifacts);
        assertTrue(search.getContent().contains("src/A.java:123:exact matching source\n"));
        assertTrue(search.getContent().contains("additional matches omitted"));
        assertTrue(search.getContent().length() <= 1000);
        String id = search.getMetadataAs(ToolResultPolicy.ARTIFACT);
        assertEquals(matches, artifacts.restore(id, 0, 16000).get("content"));
    }

    @Test void taskConstraintsPersistAndUnsupportedChangesAreAtomic() {
        ContextArtifactStore artifacts = new ContextArtifactStore(directory);
        TaskStateStore state = new TaskStateStore(directory, artifacts);
        Map<String, Object> patch = new LinkedHashMap<>();
        patch.put("objective", "Fix A and B");
        patch.put("constraints", Collections.singletonList("Keep API compatible"));
        patch.put("acceptance", Collections.singletonList("Tests pass"));
        state.update(patch);
        state.update(Collections.singletonMap("constraints", Collections.singletonList("Use JDK8")));
        assertEquals(Arrays.asList("Keep API compatible", "Use JDK8"), new TaskStateStore(directory, artifacts).get().get("constraints"));
        Object before = state.get();
        assertThrows(IllegalArgumentException.class, () -> state.update(Collections.singletonMap("objective", "Different objective")));
        assertThrows(IllegalArgumentException.class, () -> state.update(Collections.singletonMap("evidence", Collections.singletonList(repeat("a", 64)))));
        assertEquals(before, state.get());
        String id = artifacts.put("read", Collections.singletonMap("path", "A.java"), "class A {}");
        state.update(Collections.singletonMap("evidence", Collections.singletonList(id)));
        assertEquals(Collections.singletonList(id), state.get().get("evidence"));
    }

    @Test void metricsKeepUnknownUsageDistinctFromZeroAndSurviveRestart() {
        ContextMetrics metrics = new ContextMetrics(directory);
        ChatMessage question = ChatMessage.ofUser("same question");
        metrics.request(Collections.singletonList(question));
        metrics.request(Arrays.asList(question, ChatMessage.ofAssistant("answer")));
        metrics.usage("test", 10, null);
        metrics.usage("test", 20, new AiUsage(100, 0, 20, 120, 40, 60, null));
        Map<String, Object> report = new ContextMetrics(directory).report();
        Map<?, ?> last = (Map<?, ?>) report.get("lastRequest");
        assertEquals(1, ((Number) last.get("commonMessagePrefix")).intValue());
        assertEquals(false, last.get("cacheHitEstimate"));
        assertEquals(60L, ((Number) ((Map<?, ?>) report.get("providerReportedReasonUsage")).get("cacheReadInputTokens")).longValue());
        assertEquals(false, report.get("costMeasurementComplete"));
    }

    @Test void clearingSessionAlsoClearsArtifactsTaskStateAndMetrics() {
        ModelContextAgentSession session = new ModelContextAgentSession("clear", directory.toString());
        String id = session.contextArtifacts().put("read", Collections.emptyMap(), "old");
        session.taskState().update(Collections.singletonMap("objective", "old task"));
        session.contextMetrics().event("old", Collections.emptyMap());
        session.clear();
        ModelContextAgentSession reopened = new ModelContextAgentSession("clear", directory.toString());
        assertFalse(reopened.contextArtifacts().contains(id));
        assertTrue(reopened.taskState().get().isEmpty());
        assertTrue(((Map<?, ?>) reopened.contextMetrics().report().get("events")).isEmpty());
    }

    private static ToolMessage tool(String name, String content) { return new ToolMessage(ToolResult.success(content), name, "call-1", false); }
    private static String repeat(String value, int count) { StringBuilder b = new StringBuilder(); for (int i = 0; i < count; i++) b.append(value); return b.toString(); }
}
