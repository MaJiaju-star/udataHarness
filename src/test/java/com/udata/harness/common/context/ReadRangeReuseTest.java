package com.udata.harness.common.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.noear.solon.ai.chat.message.*;
import org.noear.solon.ai.chat.tool.ToolResult;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ReadRangeReuseTest {
    @TempDir Path directory;
    private final ToolResultPolicy policy = new ToolResultPolicy(16000);
    private Map<String,Object> args(String path, int start, int end) {
        Map<String,Object> args = new LinkedHashMap<>(); args.put("file_path",path); args.put("offset",start); args.put("limit",end-start+1); return args;
    }
    static String read(String path, int start, int end, int changed) {
        StringBuilder out = new StringBuilder("--- File: " + path + " (Lines: " + start + " - " + end + ", Size: 10.00 KB) ---\n--------------------------------------------------\n");
        for (int i=start; i<=end; i++) out.append(String.format("%6d | %s\n", i, (i == changed ? "CHANGED " : "source ") + String.join("", Collections.nCopies(70,"x"))));
        return out.append("\n--- [文件读取完毕] ---").toString();
    }
    private ToolMessage tool(String text) { return new ToolMessage(ToolResult.success(text),"read","id",false); }
    @Test void overlappingReadKeepsOnlyNewLinesAndArchiveRemainsComplete() {
        ContextArtifactStore store = new ContextArtifactStore(directory);
        ToolMessage first = policy.project(tool(read("A.java",100,200,-1)), args("A.java",100,200), Collections.emptyList(), store);
        String raw = read("A.java",150,250,-1);
        ToolMessage next = policy.project(tool(raw), args("A.java",150,250), Collections.singletonList(first), store);
        assertEquals("range_delta", next.getMetadataAs(ToolResultPolicy.PROJECTION));
        assertTrue(next.getContent().contains("reused visible lines 150-200"));
        assertTrue(next.getContent().contains("201 | source"));
        assertTrue(next.getContent().length() < raw.length());
        assertEquals(raw, store.restore(next.getMetadataAs(ToolResultPolicy.ARTIFACT),0,16000).get("content"));
    }
    @Test void changedLineIsShownAndReferenceChainsCannotSupplyMissingLines() {
        ContextArtifactStore store = new ContextArtifactStore(directory);
        ToolMessage first = policy.project(tool(read("A.java",100,200,-1)), args("A.java",100,200), Collections.emptyList(), store);
        ToolMessage changed = policy.project(tool(read("A.java",150,250,175)), args("A.java",150,250), Collections.singletonList(first), store);
        assertTrue(changed.getContent().contains("175 | CHANGED"));
        assertFalse(changed.getContent().contains("reused visible lines 150-200"));
        ToolMessage reread = policy.project(tool(read("A.java",150,200,-1)), args("A.java",150,200), Collections.singletonList(changed), store);
        assertTrue(reread.getContent().contains("150 | source"));
    }
    @Test void differentPathsWorkspacesAndExitedEvidenceAreNotReused() {
        ContextArtifactStore store = new ContextArtifactStore(directory);
        Map<String,Object> oldArgs = args("A.java",100,200); oldArgs.put("_context_cwd","workspace1");
        ToolMessage first = policy.project(tool(read("A.java",100,200,-1)),oldArgs,Collections.emptyList(),store);
        Map<String,Object> other = args("A.java",150,250); other.put("_context_cwd","workspace2");
        assertEquals("full",policy.project(tool(read("A.java",150,250,-1)),other,Collections.singletonList(first),store).getMetadataAs(ToolResultPolicy.PROJECTION));
        assertEquals("full",policy.project(tool(read("B.java",150,250,-1)),args("B.java",150,250),Collections.singletonList(first),store).getMetadataAs(ToolResultPolicy.PROJECTION));
        assertEquals("full",policy.project(tool(read("A.java",150,250,-1)),oldArgs,Collections.emptyList(),store).getMetadataAs(ToolResultPolicy.PROJECTION));
    }
    @Test void onlyWholeLinesVisibleInLargeExcerptsCanBeReused() {
        ToolResultPolicy bounded = new ToolResultPolicy(2000); ContextArtifactStore store = new ContextArtifactStore(directory);
        ToolMessage old = bounded.project(tool(read("A.java",1,200,-1)),args("A.java",1,200),Collections.emptyList(),store);
        ToolMessage tail = bounded.project(tool(read("A.java",195,200,-1)),args("A.java",195,200),Collections.singletonList(old),store);
        assertEquals("range_delta",tail.getMetadataAs(ToolResultPolicy.PROJECTION));
        ToolMessage middle = bounded.project(tool(read("A.java",90,95,-1)),args("A.java",90,95),Collections.singletonList(old),store);
        assertEquals("full",middle.getMetadataAs(ToolResultPolicy.PROJECTION));
        assertTrue(middle.getContent().contains("90 | source"));
    }
}
