package org.noear.solon.ai.talents.cli;

import com.udata.harness.common.context.ContextArtifactStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class EditDiffTest {
    @TempDir Path directory;

    @Test
    void reportsSeparateChangesWithContextAndCorrectLineNumbers() {
        String before = "first\nold\n3\n4\n5\n6\n7\n8\n9\nlast\n";
        String after = before.replace("old", "new").replace("last", "final");
        Map<String, Object> diff = EditDiff.create("sample.txt", before, after).details;
        assertEquals(2, diff.get("addedLines"));
        assertEquals(2, diff.get("deletedLines"));
        assertEquals(2, diff.get("firstChangedLine"));
        String patch = (String) diff.get("patch");
        assertTrue(patch.startsWith("--- a/sample.txt\n+++ b/sample.txt\n@@"));
        assertTrue(patch.contains("-old\n+new\n"));
        assertTrue(patch.contains("-last\n+final\n"));
        assertFalse(patch.contains("No newline"));
    }

    @Test
    void preservesBomAndCrLfAndRepresentsMissingFinalNewline() {
        Map<String, Object> diff = EditDiff.create("a.txt", "\uFEFFfirst\r\nold", "\uFEFFfirst\r\nnew\r\n").details;
        assertEquals(Boolean.TRUE, diff.get("bomPreserved"));
        String patch = (String) diff.get("patch");
        assertTrue(patch.contains("-old\n\\ No newline at end of file\n+new\n"));
        assertFalse(patch.contains("\r"));
        Map<String, Object> newlineOnly = EditDiff.create("newline.txt", "a", "a\n").details;
        assertEquals(1, newlineOnly.get("addedLines"));
        assertEquals(1, newlineOnly.get("deletedLines"));
        Map<String, Object> crOnly = EditDiff.create("cr.txt", "first\rold\r", "first\rnew\r").details;
        assertEquals(2, crOnly.get("firstChangedLine"));
        assertTrue(((String) crOnly.get("patch")).contains("-old\n+new\n"));
    }

    @Test
    void boundsLargeDiffAndArchivesFullPatch() throws Exception {
        ContextArtifactStore store = new ContextArtifactStore(directory);
        StringBuilder before = new StringBuilder(), after = new StringBuilder();
        for (int i = 0; i < 3000; i++) {
            before.append("old-line-").append(i).append('\n');
            after.append("new-line-").append(i).append('\n');
        }
        EditDiff prepared = EditDiff.create("large.txt", before.toString(), after.toString());
        prepared.archive(store, "s");
        Map<String, Object> diff = prepared.details;
        assertEquals(Boolean.TRUE, diff.get("coarse"));
        assertEquals(Boolean.TRUE, diff.get("truncated"));
        assertTrue(((String) diff.get("patch")).length() <= EditDiff.VISIBLE_PATCH_CHARS);
        String path = (String) diff.get("patchPath");
        String id = path.substring("@tool-results/".length(), path.length() - 4);
        assertTrue(store.load("s", id).chars > EditDiff.VISIBLE_PATCH_CHARS);
        assertTrue(store.restore("s", id, 0, 1000).contains("--- a/large.txt"));
    }
}
