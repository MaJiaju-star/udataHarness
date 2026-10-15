package com.udata.harness.common.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ContextArtifactStoreTest {
    @TempDir Path directory;

    @Test
    void keepsFullContentAndFrozenPreviewAcrossRestart() throws Exception {
        ContextArtifactStore store = new ContextArtifactStore(directory);
        String text = "first\n" + repeat("日志😀\n", 200) + "MIDDLE_EVIDENCE\ntail";
        ContextArtifactStore.Artifact saved = store.save("session-a", "call-1", "bash", text, "limit", 10);
        assertFalse(saved.projection.contains("MIDDLE_EVIDENCE"));
        ContextArtifactStore reloaded = new ContextArtifactStore(directory);
        ContextArtifactStore.Artifact replayed = reloaded.save("session-a", "call-1", "bash", text, "changed", 999);
        assertEquals(saved.projection, replayed.projection);
        String restored = reloaded.restore("session-a", saved.id, 0, text.length());
        assertEquals(text, restored.substring(restored.indexOf('\n') + 1));
        assertThrows(IOException.class, () -> store.save("session-a", "call-1", "bash", "different", "limit", 10));
    }

    @Test
    void isolatesSessionsOwnersAndArtifactPaths() throws Exception {
        ContextArtifactStore store = new ContextArtifactStore(directory.resolve("alice"));
        ContextArtifactStore.Artifact saved = store.save("a", "call", "read", "content", "limit", 2);
        assertThrows(IOException.class, () -> store.restore("b", saved.id, 0, 100));
        assertThrows(IOException.class, () -> new ContextArtifactStore(directory.resolve("bob"))
                .restore("a", saved.id, 0, 100));
        assertThrows(IOException.class, () -> store.restore("a", "../outside", 0, 100));
        assertThrows(IOException.class, () -> store.restore(null, saved.id, 0, 100));
    }

    @Test
    void returnsBoundedPagesWithoutSplittingSurrogatePairs() throws Exception {
        ContextArtifactStore store = new ContextArtifactStore(directory);
        ContextArtifactStore.Artifact saved = store.save("a", "call", "read", "A😀BC", "limit", 2);
        assertFalse(saved.projection.contains("\uD83D\n"));
        String page = store.restore("a", saved.id, 0, 2);
        assertTrue(page.contains("next_offset=1; has_more=true"));
        assertTrue(page.endsWith("\nA"));
        assertTrue(store.restore("a", saved.id, 1, 2).endsWith("\n😀"));
        assertTrue(store.restore("a", saved.id, 5, 2).endsWith("\n"));
        assertThrows(IOException.class, () -> store.restore("a", saved.id, -1, 2));
        assertThrows(IOException.class, () -> store.restore("a", saved.id, 6, 2));
        assertThrows(IOException.class, () -> store.restore("a", saved.id, 1, 1));
    }

    @Test
    void restoreToolCapsRequestsAndDoesNotRevealOtherSessions() throws Exception {
        ContextArtifactStore store = new ContextArtifactStore(directory);
        ContextArtifactStore.Artifact saved = store.save("a", "call", "grep", "abcdefghij", "limit", 2);
        ContextRestoreTool tool = new ContextRestoreTool(store, 4);
        assertTrue(tool.restore(saved.id, 0, Integer.MAX_VALUE, "a").endsWith("\nabcd"));
        assertTrue(tool.restore(saved.id, 0, 100, "b").contains("unavailable"));
        assertTrue(tool.restore(saved.id, 0, 0, "a").contains("Invalid"));
    }

    @Test
    void rejectsMissingBodyInsteadOfReturningADeadReference() throws Exception {
        ContextArtifactStore store = new ContextArtifactStore(directory);
        ContextArtifactStore.Artifact saved = store.save("a", "call", "bash", "text", "limit", 2);
        Path body = directory.resolve(ContextArtifactStore.digest("a")).resolve(saved.id + ".txt");
        Files.delete(body);
        assertThrows(IOException.class, () -> store.save("a", "call", "bash", "text", "limit", 2));
    }

    @Test
    void rejectsInvalidConfigurationBeforeEnablingProjection() {
        assertThrows(IllegalArgumentException.class, () -> new ContextManagementOptions(100, 200, 100, 10));
        assertThrows(IllegalArgumentException.class, () -> new ContextManagementOptions(100, 0, 10, 10));
        assertThrows(IllegalArgumentException.class, () -> new ContextManagementOptions(100, 200, 10, 0));
    }

    static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }
}
