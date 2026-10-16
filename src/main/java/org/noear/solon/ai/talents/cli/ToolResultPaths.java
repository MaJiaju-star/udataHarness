package org.noear.solon.ai.talents.cli;

import com.udata.harness.common.context.ContextArtifactStore;

import java.io.IOException;
import java.nio.file.Path;

/** Session-bound, read-only virtual names for committed tool-result snapshots. */
final class ToolResultPaths {
    static final String ROOT = "@tool-results";
    private final ContextArtifactStore store;

    ToolResultPaths(ContextArtifactStore store) {
        this.store = store;
    }

    static boolean isVirtual(String value) {
        return value != null && (value.equals(ROOT) || value.startsWith(ROOT + "/"));
    }

    Path resolve(String value, String sessionId, boolean allowDirectory) throws IOException {
        if (store == null || sessionId == null || sessionId.isEmpty()) {
            throw new IOException("Tool-result snapshots are unavailable in this session");
        }
        if (ROOT.equals(value) && allowDirectory) return store.sessionArtifacts(sessionId);
        if (!value.matches("@tool-results/[a-f0-9]{64}\\.txt")) {
            throw new IOException("Invalid tool-result path");
        }
        return store.bodyPath(sessionId, value.substring(ROOT.length() + 1, value.length() - 4));
    }
}
