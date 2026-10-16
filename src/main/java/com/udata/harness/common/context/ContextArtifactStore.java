package com.udata.harness.common.context;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Properties;
import java.util.Map;
import java.util.Collections;
import java.util.List;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.stream.Stream;
import java.nio.file.StandardOpenOption;
import org.noear.snack4.ONode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Full text and frozen projections, scoped by the owning engine and session. */
public class ContextArtifactStore {
    private static final Logger LOG = LoggerFactory.getLogger(ContextArtifactStore.class);
    private final Path root;

    public ContextArtifactStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** Commit content before metadata. An incomplete write never authorizes removal. */
    public synchronized Artifact save(String sessionId, String invocationId, String toolName,
                                      String content, String reason, int previewChars) throws IOException {
        return save(sessionId, invocationId, toolName, content, reason, previewChars, Collections.emptyMap());
    }

    public synchronized Artifact save(String sessionId, String invocationId, String toolName,
                                      String content, String reason, int previewChars,
                                      Map<String, String> source) throws IOException {
        requireSession(sessionId);
        if (invocationId == null || invocationId.isEmpty()) throw new IOException("Missing invocation ID");
        String id = digest(invocationId);
        String hash = digest(content);
        Path directory = sessionDirectory(sessionId);
        Path metadata = directory.resolve(id + ".properties");
        Path body = directory.resolve(id + ".txt");
        if (Files.exists(metadata)) {
            Artifact existing = load(sessionId, id);
            if (!hash.equals(existing.sha256) || !toolName.equals(existing.toolName)) {
                throw new IOException("Context invocation ID has conflicting content");
            }
            return existing;
        }
        Files.createDirectories(directory);
        // Repair an interrupted commit only when the already-written body is identical.
        if (Files.exists(body)) {
            if (!hash.equals(digest(new String(Files.readAllBytes(body), StandardCharsets.UTF_8)))) {
                throw new IOException("Context artifact has conflicting content");
            }
        } else {
            writeBody(body, content);
        }
        String projection = preview(invocationId, content, reason, previewChars);
        Properties values = new Properties();
        values.setProperty("tool", toolName);
        values.setProperty("invocationId", invocationId);
        values.setProperty("sha256", hash);
        values.setProperty("chars", Integer.toString(content.length()));
        values.setProperty("reason", reason);
        values.setProperty("createdAt", Long.toString(System.currentTimeMillis()));
        values.setProperty("projection", projection);
        for (String key : new String[]{"taskId", "sourcePath", "sourceHash", "sourceRange"}) {
            String value = source.get(key);
            if (value != null && value.length() <= 4000) values.setProperty(key, value);
        }
        writeProperties(metadata, values);
        recordAction(sessionId, "archive", id, reason, content.length());
        return load(sessionId, id);
    }

    static String preview(String invocationId, String content, String reason, int previewChars) {
        int end = safeEnd(content, Math.min(content.length(), previewChars));
        String id = digest(invocationId);
        return "<context-artifact id=\"" + id + "\">\n"
                + "Tool result archived (" + reason + "). chars=" + content.length()
                + "; sha256=" + digest(content) + "\n"
                + "This is an immutable historical tool-result snapshot, not the current workspace file.\n"
                + "Use read(file_path=\"@tool-results/" + id
                + ".txt\") to retrieve a bounded range; grep(path=\"@tool-results\") searches this session.\n"
                + "Preview:\n" + content.substring(0, end)
                + (end < content.length() ? "\n[Preview truncated]" : "") + "\n</context-artifact>";
    }

    public Artifact load(String sessionId, String artifactId) throws IOException {
        requireSession(sessionId);
        if (artifactId == null || !artifactId.matches("[a-f0-9]{64}")) {
            throw new IOException("Invalid context artifact ID");
        }
        Path directory = sessionDirectory(sessionId);
        Properties values = readProperties(directory.resolve(artifactId + ".properties"));
        Path body = directory.resolve(artifactId + ".txt");
        if (!Files.isRegularFile(body)) throw new IOException("Context artifact content is missing");
        try {
            String tool = values.getProperty("tool");
            String hash = values.getProperty("sha256");
            String projection = values.getProperty("projection");
            int chars = Integer.parseInt(values.getProperty("chars"));
            if (tool == null || hash == null || !hash.matches("[a-f0-9]{64}")
                    || projection == null || chars < 0) throw new IllegalArgumentException("Incomplete metadata");
            return new Artifact(artifactId, tool, hash, chars, projection, body, values);
        } catch (RuntimeException e) {
            throw new IOException("Invalid context artifact metadata", e);
        }
    }

    /** Resolve only a committed artifact owned by this session; never accepts filesystem paths. */
    public Path bodyPath(String sessionId, String artifactId) throws IOException {
        return load(sessionId, artifactId).body;
    }

    /** The directory is only for internal bounded search, not a public filesystem mount. */
    public Path sessionArtifacts(String sessionId) throws IOException {
        return sessionDirectory(sessionId);
    }

    /** UTF-16 character offsets; normalize surrogate boundaries without losing characters. */
    public String restore(String sessionId, String artifactId, int offset, int limit) throws IOException {
        Artifact artifact = load(sessionId, artifactId);
        if (offset < 0 || offset > artifact.chars || limit < 1) {
            throw new IOException("Invalid context restore range");
        }
        int start = offset;
        String text;
        try (Reader reader = Files.newBufferedReader(artifact.body, StandardCharsets.UTF_8)) {
            long remaining = start;
            while (remaining > 0) {
                long skipped = reader.skip(remaining);
                if (skipped == 0) throw new IOException("Context artifact content is incomplete");
                remaining -= skipped;
            }
            char[] buffer = new char[Math.min(limit, artifact.chars - start)];
            int count = 0;
            while (count < buffer.length) {
                int read = reader.read(buffer, count, buffer.length - count);
                if (read < 0) throw new IOException("Context artifact content is incomplete");
                count += read;
            }
            text = new String(buffer, 0, count);
        }
        // An offset inside a surrogate pair moves forward to the next complete character.
        if (!text.isEmpty() && Character.isLowSurrogate(text.charAt(0))) {
            text = text.substring(1);
            start++;
        }
        int end = safeEnd(text, text.length());
        text = text.substring(0, end);
        int next = start + text.length();
        if (text.isEmpty() && next < artifact.chars) {
            throw new IOException("Range too small for a Unicode character; use limit >= 2");
        }
        recordAction(sessionId, "restore", artifactId, "range:" + start, text.length());
        return "Historical tool-result snapshot; tool=" + artifact.toolName
                + "; sha256=" + artifact.sha256 + "; total_chars=" + artifact.chars
                + "; offset=" + start + "; next_offset=" + next
                + "; has_more=" + (next < artifact.chars) + "\n" + text;
    }

    Path sessionDirectory(String sessionId) throws IOException {
        requireSession(sessionId);
        return root.resolve(digest(sessionId));
    }

    static void writeProperties(Path target, Properties values) throws IOException {
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), "metadata-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temporary)) { values.store(out, "Context metadata v2"); }
            commit(temporary, target);
        } finally { Files.deleteIfExists(temporary); }
    }

    static Properties readProperties(Path file) throws IOException {
        Properties values = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            values.load(in);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid context metadata encoding", e);
        }
        return values;
    }

    Properties metadata(String sessionId, String artifactId) throws IOException {
        return (Properties) load(sessionId, artifactId).metadata.clone();
    }

    /** Bounded literal search. Cursor is an artifact-list offset; body scan truncation is explicit. */
    public String search(String sessionId, String query, String taskId, String sourcePath, int offset, int limit)
            throws IOException {
        return search(sessionId, query, taskId, sourcePath, offset, limit, 0);
    }

    public String search(String sessionId, String query, String taskId, String sourcePath, int offset, int limit,
                         int bodyOffset) throws IOException {
        if (query == null || query.isEmpty() || query.length() > 256 || offset < 0 || limit < 1 || bodyOffset < 0) {
            throw new IOException("Invalid search arguments");
        }
        Path directory = sessionDirectory(sessionId);
        if (!Files.exists(directory)) return "No context artifacts in this session.";
        List<Path> files = new ArrayList<>();
        try (Stream<Path> paths = Files.list(directory)) {
            paths.filter(p -> p.getFileName().toString().matches("[a-f0-9]{64}\\.properties"))
                    .sorted(Comparator.comparing(p -> p.getFileName().toString())).forEach(files::add);
        }
        StringBuilder result = new StringBuilder("Literal, case-sensitive historical search; snapshots are not current files.\n");
        int cursor = Math.min(offset, files.size());
        int scanned = 0;
        int matches = 0;
        int truncated = 0;
        int nextBodyOffset = 0;
        while (cursor < files.size() && scanned < 20 && matches < Math.min(limit, 10)) {
            String id = files.get(cursor++).getFileName().toString().substring(0, 64);
            scanned++;
            Artifact artifact;
            try { artifact = load(sessionId, id); }
            catch (IOException e) { continue; } // One incomplete artifact does not hide healthy snapshots.
            Properties meta = artifact.metadata;
            if (taskId != null && !taskId.isEmpty() && !taskId.equals(meta.getProperty("taskId", ""))) continue;
            if (sourcePath != null && !sourcePath.isEmpty()
                    && !meta.getProperty("sourcePath", "").contains(sourcePath)) continue;
            int base = cursor - 1 == offset ? bodyOffset : 0;
            if (base > artifact.chars) throw new IOException("Invalid body offset");
            int cap = Math.min(artifact.chars - base, 100000);
            StringBuilder body = new StringBuilder(cap);
            try (Reader reader = Files.newBufferedReader(artifact.body, StandardCharsets.UTF_8)) {
                long remaining = base;
                while (remaining > 0) {
                    long skipped = reader.skip(remaining);
                    if (skipped == 0) throw new IOException("Incomplete context body");
                    remaining -= skipped;
                }
                char[] buffer = new char[4096];
                while (body.length() < cap) {
                    int count = reader.read(buffer, 0, Math.min(buffer.length, cap - body.length()));
                    if (count < 0) break;
                    body.append(buffer, 0, count);
                }
            }
            boolean incomplete = artifact.chars > base + body.length();
            if (incomplete) truncated++;
            int hit = body.indexOf(query);
            if (hit >= 0) {
                matches++;
                int start = Math.max(0, hit - 120);
                if (start > 0 && Character.isLowSurrogate(body.charAt(start))) start++;
                int end = safeEnd(body.toString(), Math.min(body.length(), hit + query.length() + 240));
                result.append("artifact_id=").append(id).append("; offset=").append(base + start)
                        .append("; match_offset=").append(base + hit).append("; task_id=").append(meta.getProperty("taskId", ""))
                        .append("; source_path=").append(meta.getProperty("sourcePath", ""))
                        .append("\n").append(body.substring(start, end)).append("\n\n");
            } else if (incomplete) {
                // Continue the same body next call, overlapping to find keywords across chunk boundaries.
                cursor--;
                nextBodyOffset = base + body.length() - query.length() + 1;
                break;
            }
        }
        result.append("matches=").append(matches).append("; next_offset=").append(cursor)
                .append("; has_more=").append(cursor < files.size()).append("; body_scan_truncated=").append(truncated)
                .append("; next_body_offset=").append(nextBodyOffset)
                .append("\nSearch scans at most 20 artifacts and 100000 UTF-16 characters per body per call. "
                        + "Continue with offset=next_offset and body_offset=next_body_offset. One match per artifact.");
        recordAction(sessionId, "search", "", "matches:" + matches + "; scanned:" + scanned, 0);
        return result.toString();
    }

    /** Audit is best effort: audit failures must never destroy a usable original or restored result. */
    synchronized void recordAction(String sessionId, String action, String id, String reason, int chars) {
        try {
            Path directory = sessionDirectory(sessionId);
            Files.createDirectories(directory);
            Map<String, Object> event = new java.util.LinkedHashMap<>();
            event.put("timestamp", System.currentTimeMillis());
            event.put("action", action);
            event.put("id", id);
            event.put("reason", reason);
            event.put("chars", chars);
            Files.write(directory.resolve("context-actions.jsonl"),
                    (ONode.serialize(event) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warn("Context action audit failed ({})", e.getClass().getSimpleName());
        }
    }

    public synchronized void recordObservation(String sessionId, Map<String, Object> event) {
        appendEvent(sessionId, "context-observations.jsonl", event);
    }

    synchronized void recordDecision(String sessionId, Map<String, Object> event) {
        appendEvent(sessionId, "context-actions.jsonl", event);
    }

    private void appendEvent(String sessionId, String filename, Map<String, Object> event) {
        try {
            Path directory = sessionDirectory(sessionId);
            Files.createDirectories(directory);
            Files.write(directory.resolve(filename), (ONode.serialize(event) + "\n").getBytes(StandardCharsets.UTF_8),
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            LOG.warn("Context telemetry unavailable ({})", e.getClass().getSimpleName());
        }
    }

    /** Keep only a bounded tail in memory; tolerate an interrupted final JSONL write. */
    public List<Map<String, Object>> observations(String sessionId, boolean actions, int limit) throws IOException {
        Path file = sessionDirectory(sessionId).resolve(actions ? "context-actions.jsonl" : "context-observations.jsonl");
        java.util.Deque<Map<String, Object>> tail = new java.util.ArrayDeque<>();
        if (!Files.exists(file)) return new ArrayList<>();
        int count = Math.max(1, Math.min(limit, 500));
        try (java.io.BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                try {
                    if (line.trim().isEmpty()) continue;
                    Map<String, Object> event = ONode.ofJson(line).toBean(Map.class);
                    if (event == null) continue;
                    tail.addLast(event);
                    if (tail.size() > count) tail.removeFirst();
                } catch (RuntimeException ignored) { /* A malformed record does not hide healthy telemetry. */ }
            }
        }
        return new ArrayList<>(tail);
    }

    private static void requireSession(String sessionId) throws IOException {
        if (sessionId == null || sessionId.isEmpty()) throw new IOException("Missing context session");
    }

    private static void writeBody(Path target, String content) throws IOException {
        Path temporary = Files.createTempFile(target.getParent(), "content-", ".tmp");
        try {
            Files.write(temporary, content.getBytes(StandardCharsets.UTF_8));
            commit(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void commit(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static int safeEnd(String content, int end) {
        if (end > 0 && Character.isHighSurrogate(content.charAt(end - 1))) return end - 1;
        return end;
    }

    static String digest(String value) {
        return digestBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    static String digestBytes(byte[] value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value);
            char[] hex = "0123456789abcdef".toCharArray();
            char[] result = new char[bytes.length * 2];
            for (int i = 0; i < bytes.length; i++) {
                result[i * 2] = hex[(bytes[i] & 0xff) >>> 4];
                result[i * 2 + 1] = hex[bytes[i] & 15];
            }
            return new String(result);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static final class Artifact {
        public final String id;
        public final String toolName;
        public final String sha256;
        public final int chars;
        public final String projection;
        private final Path body;
        private final Properties metadata;

        private Artifact(String id, String toolName, String sha256, int chars, String projection, Path body, Properties metadata) {
            this.id = id;
            this.toolName = toolName;
            this.sha256 = sha256;
            this.chars = chars;
            this.projection = projection;
            this.body = body;
            this.metadata = metadata;
        }
    }
}
