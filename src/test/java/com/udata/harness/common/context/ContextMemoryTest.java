package com.udata.harness.common.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static com.udata.harness.common.context.ContextArtifactStoreTest.repeat;

class ContextMemoryTest {
    @TempDir Path directory;

    @Test
    void switchesTasksAtomicallyAndPreservesFactsAcrossRestart() throws Exception {
        ContextArtifactStore artifacts = new ContextArtifactStore(directory);
        ContextTaskStore tasks = new ContextTaskStore(artifacts);
        tasks.update("s", "A", "auth", "active", "Keep public API; depends on B", null, null, "Run tests");
        tasks.update("s", "B", "orders", "active", "Preserve auth contract", null, null, null);
        ContextTaskStore restarted = new ContextTaskStore(new ContextArtifactStore(directory));
        assertEquals("B", restarted.currentTaskId("s"));
        assertTrue(restarted.snapshot("s", "A").contains("state: paused"));
        assertTrue(restarted.snapshot("s", "A").contains("Keep public API; depends on B"));
        assertTrue(restarted.snapshot("s", null).contains("task_id=B"));
        assertTrue(restarted.snapshot("other", "A").contains("not found"));
    }

    @Test
    void rejectsUnverifiedClosureWithoutChangingExistingTask() throws Exception {
        ContextTaskStore tasks = new ContextTaskStore(new ContextArtifactStore(directory));
        tasks.update("s", "A", "auth", "active", "Constraint", null, null, null);
        assertThrows(IOException.class, () -> tasks.update("s", "A", null, "closed", null, "Fixed", null, null));
        assertEquals("active", tasks.state("s", "A"));
        assertFalse(tasks.snapshot("s", "A").contains("Fixed"));
        tasks.update("s", "A", null, "closed", null, "Fixed", "mvn test: 10 passed; evidence ref", null);
        assertEquals("closed", tasks.state("s", "A"));
        assertEquals("", tasks.currentTaskId("s"));
        tasks.update("s", "A", null, "active", null, null, null, null);
        assertThrows(IOException.class, () -> tasks.update("s", "A", null, "closed", null, null, null, null));
        assertThrows(IOException.class, () -> tasks.update("s", "../A", null, "active", null, null, null, null));
        assertThrows(IOException.class, () -> tasks.update("s", "B", null, "active", repeat("x", 4001), null, null, null));
    }

    @Test
    void findsMiddleEvidenceWithTaskAndSourceFiltersAndRestorableOffsets() throws Exception {
        ContextArtifactStore store = new ContextArtifactStore(directory);
        Map<String, String> meta = new HashMap<>();
        meta.put("taskId", "A"); meta.put("sourcePath", "src/Auth.java");
        String body = repeat("日志😀\n", 100) + "MIDDLE_FAILURE" + repeat("tail", 100);
        ContextArtifactStore.Artifact artifact = store.save("s", "call", "read", body, "limit", 10, meta);
        String search = new ContextArtifactStore(directory).search("s", "MIDDLE_FAILURE", "A", "Auth", 0, 5);
        assertTrue(search.contains("artifact_id=" + artifact.id));
        assertTrue(search.contains("match_offset=" + body.indexOf("MIDDLE_FAILURE")));
        assertTrue(store.restore("s", artifact.id, body.indexOf("MIDDLE_FAILURE"), 14).endsWith("MIDDLE_FAILURE"));
        assertFalse(store.search("s", "MIDDLE_FAILURE", "B", null, 0, 5).contains(artifact.id));
        assertFalse(store.search("other", "MIDDLE_FAILURE", null, null, 0, 5).contains(artifact.id));
        assertThrows(IOException.class, () -> store.search("s", "", null, null, 0, 5));
    }

    @Test
    void continuesLargeBodySearchAcrossBoundaryWithoutLosingEvidence() throws Exception {
        ContextArtifactStore store = new ContextArtifactStore(directory);
        String body = repeat("x", 99997) + "BOUNDARY_EVIDENCE" + repeat("z", 500);
        store.save("s", "call", "bash", body, "limit", 10);
        String first = store.search("s", "BOUNDARY_EVIDENCE", null, null, 0, 5);
        assertTrue(first.contains("matches=0; next_offset=0; has_more=true"));
        int continuation = 100000 - "BOUNDARY_EVIDENCE".length() + 1;
        String next = store.search("s", "BOUNDARY_EVIDENCE", null, null, 0, 5, continuation);
        assertTrue(next.contains("match_offset=99997"));
        assertTrue(next.contains("matches=1; next_offset=1; has_more=false"));
    }

    @Test
    void metadataPaginationSkipsDamagedArtifactsAndCapsResults() throws Exception {
        ContextArtifactStore store = new ContextArtifactStore(directory);
        for (int i = 0; i < 23; i++) store.save("s", "call" + i, "bash", "needle " + i, "limit", 1);
        Path bad = store.sessionDirectory("s").resolve(repeat("0", 64) + ".properties");
        Files.write(bad, "bad=metadata".getBytes(StandardCharsets.UTF_8));
        String first = store.search("s", "needle", null, null, 0, Integer.MAX_VALUE);
        assertTrue(first.contains("matches=10; next_offset=11; has_more=true"));
        String next = store.search("s", "needle", null, null, 11, 10);
        assertTrue(next.contains("matches=10; next_offset=21; has_more=true"));
    }

    @Test
    void detectsFileChangesAndRejectsSourcesOutsideWorkspace() throws Exception {
        Path workspace = Files.createDirectories(directory.resolve("workspace"));
        Path file = workspace.resolve("A.java");
        Files.write(file, "version one".getBytes(StandardCharsets.UTF_8));
        Map<String, String> metadata = new HashMap<>();
        ContextSourceVersion.capture(workspace, java.util.Collections.<String, Object>singletonMap("file_path", "A.java"), metadata);
        ContextArtifactStore store = new ContextArtifactStore(directory.resolve("artifacts"));
        ContextArtifactStore.Artifact artifact = store.save("s", "read", "read", "version one", "limit", 2, metadata);
        ContextRestoreTool tool = new ContextRestoreTool(store, 100, workspace);
        assertTrue(tool.restore(artifact.id, 0, 100, "s").contains("unchanged_since_archive_observation"));
        Files.write(file, "version two".getBytes(StandardCharsets.UTF_8));
        String restored = tool.restore(artifact.id, 0, 100, "s");
        assertTrue(restored.contains("source_version=changed"));
        assertTrue(restored.endsWith("\nversion one"));
        Files.delete(file);
        assertTrue(tool.restore(artifact.id, 0, 100, "s").contains("source_version=unavailable"));
        Map<String, String> outside = new HashMap<>();
        ContextSourceVersion.capture(workspace, java.util.Collections.<String, Object>singletonMap("file_path", "../outside"), outside);
        assertTrue(outside.isEmpty());
    }

    @Test
    void recordsActionsWithoutPayloadsAndDoesNotDuplicateArchiveOnReplay() throws Exception {
        ContextArtifactStore store = new ContextArtifactStore(directory);
        ContextArtifactStore.Artifact artifact = store.save("s", "call", "bash", "PRIVATE_PAYLOAD", "limit", 2);
        store.save("s", "call", "bash", "PRIVATE_PAYLOAD", "limit", 2);
        store.restore("s", artifact.id, 0, 5);
        store.search("s", "PRIVATE", null, null, 0, 5);
        java.util.List<String> audit = Files.readAllLines(store.sessionDirectory("s").resolve("context-actions.jsonl"), StandardCharsets.UTF_8);
        assertEquals(3, audit.size());
        assertTrue(audit.get(0).contains("archive"));
        assertTrue(audit.get(1).contains("restore"));
        assertFalse(audit.toString().contains("PRIVATE_PAYLOAD"));
    }

    @Test
    void auditFailureDoesNotDisableOriginalStorageOrRecovery() throws Exception {
        ContextArtifactStore store = new ContextArtifactStore(directory);
        Files.createDirectories(store.sessionDirectory("s").resolve("context-actions.jsonl"));
        ContextArtifactStore.Artifact artifact = store.save("s", "call", "bash", "content", "limit", 1);
        assertTrue(store.restore("s", artifact.id, 0, 7).endsWith("\ncontent"));
    }
}
