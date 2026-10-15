package com.udata.harness.common.context;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.tool.AbsToolProvider;
import org.noear.solon.annotation.Param;

import java.io.IOException;

/** The session argument is injected by Harness, never selected by the model. */
public class ContextRestoreTool extends AbsToolProvider {
    private final ContextArtifactStore store;
    private final int maxChars;

    public ContextRestoreTool(ContextArtifactStore store, int maxChars) {
        this.store = store;
        this.maxChars = maxChars;
    }

    @ToolMapping(name = "context_restore", description = "Restore a bounded character range from an archived tool-result snapshot in this session. Historical snapshots are not current workspace files. Use next_offset to page through the result.")
    public String restore(@Param(value = "artifact_id", description = "Artifact ID from a context-artifact reference") String artifactId,
                          @Param(value = "offset", required = false, defaultValue = "0", description = "Zero-based UTF-16 character offset") Integer offset,
                          @Param(value = "limit", required = false, defaultValue = "4000", description = "Maximum characters to return; server caps this value") Integer limit,
                          String __sessionId) {
        try {
            int requested = limit == null ? Math.min(4000, maxChars) : limit;
            if (requested <= 0) return "Invalid restore limit: must be positive.";
            return store.restore(__sessionId, artifactId, offset == null ? 0 : offset, Math.min(requested, maxChars));
        } catch (IOException e) {
            return "Context snapshot unavailable in this session, or invalid range. Re-read current workspace files when current content is needed.";
        }
    }
}
