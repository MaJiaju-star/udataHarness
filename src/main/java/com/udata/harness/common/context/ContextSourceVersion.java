package com.udata.harness.common.context;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

/** Fingerprint observed after execution, never an atomic snapshot of a running tool. */
final class ContextSourceVersion {
    private static final long MAX_BYTES = 2 * 1024 * 1024;

    static void capture(Path workspace, Map<String, Object> args, Map<String, String> metadata) {
        if (workspace == null || args == null) return;
        Object value = args.get("file_path");
        if (value == null) value = args.get("path");
        if (!(value instanceof String)) return;
        try {
            Path file = resolve(workspace, (String) value);
            metadata.put("sourcePath", workspace.toRealPath().relativize(file).toString().replace('\\', '/'));
            if (Files.size(file) <= MAX_BYTES) metadata.put("sourceHash", ContextArtifactStore.digestBytes(Files.readAllBytes(file)));
            StringBuilder range = new StringBuilder();
            for (String key : new String[]{"offset", "limit", "start_line", "end_line"}) {
                Object part = args.get(key);
                if (part instanceof Number) range.append(key).append('=').append(part).append(';');
            }
            metadata.put("sourceRange", range.toString());
        } catch (IOException | RuntimeException ignored) {
            // Missing provenance must not prevent safely archiving the original tool result.
        }
    }

    static String status(Path workspace, Properties metadata) {
        String source = metadata.getProperty("sourcePath", "");
        String hash = metadata.getProperty("sourceHash", "");
        if (workspace == null || source.isEmpty() || hash.isEmpty()) return "source_version=unknown";
        try {
            Path file = resolve(workspace, source);
            if (Files.size(file) > MAX_BYTES) return "source_version=unknown; re-read current file";
            boolean same = hash.equals(ContextArtifactStore.digestBytes(Files.readAllBytes(file)));
            return "source_version=" + (same ? "unchanged_since_archive_observation" : "changed; revalidate conclusions")
                    + "; source_path=" + source + "; source_range=" + metadata.getProperty("sourceRange", "")
                    + "; fingerprint observed after tool execution, not an atomic tool snapshot";
        } catch (IOException | RuntimeException e) {
            return "source_version=unavailable; re-read current file";
        }
    }

    private static Path resolve(Path workspace, String source) throws IOException {
        Path root = workspace.toRealPath();
        Path file = root.resolve(source).normalize();
        if (!file.startsWith(root)) throw new IOException("Source outside workspace");
        file = file.toRealPath();
        if (!file.startsWith(root) || !Files.isRegularFile(file)) throw new IOException("Invalid source");
        return file;
    }
}
