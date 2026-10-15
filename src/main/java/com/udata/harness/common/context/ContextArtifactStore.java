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

/** Full text and frozen projections, scoped by the owning engine and session. */
public class ContextArtifactStore {
    private final Path root;

    public ContextArtifactStore(Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    /** Commit content before metadata. An incomplete write never authorizes removal. */
    public synchronized Artifact save(String sessionId, String invocationId, String toolName,
                                      String content, String reason, int previewChars) throws IOException {
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
        int end = safeEnd(content, Math.min(content.length(), previewChars));
        String projection = "<context-artifact id=\"" + id + "\">\n"
                + "Tool result archived (" + reason + "). chars=" + content.length()
                + "; sha256=" + hash + "\n"
                + "This is an immutable historical tool-result snapshot, not the current workspace file.\n"
                + "Use context_restore(artifact_id=\"" + id
                + "\", offset=0, limit=4000) to retrieve a bounded range.\n"
                + "Preview:\n" + content.substring(0, end)
                + (end < content.length() ? "\n[Preview truncated]" : "")
                + "\n</context-artifact>";
        Properties values = new Properties();
        values.setProperty("tool", toolName);
        values.setProperty("invocationId", invocationId);
        values.setProperty("sha256", hash);
        values.setProperty("chars", Integer.toString(content.length()));
        values.setProperty("reason", reason);
        values.setProperty("createdAt", Long.toString(System.currentTimeMillis()));
        values.setProperty("projection", projection);
        Path temporary = Files.createTempFile(directory, "metadata-", ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temporary)) {
                values.store(out, "Context artifact v1");
            }
            commit(temporary, metadata);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return load(sessionId, id);
    }

    public Artifact load(String sessionId, String artifactId) throws IOException {
        requireSession(sessionId);
        if (artifactId == null || !artifactId.matches("[a-f0-9]{64}")) {
            throw new IOException("Invalid context artifact ID");
        }
        Path directory = sessionDirectory(sessionId);
        Properties values = new Properties();
        try (InputStream in = Files.newInputStream(directory.resolve(artifactId + ".properties"))) {
            values.load(in);
        }
        Path body = directory.resolve(artifactId + ".txt");
        if (!Files.isRegularFile(body)) throw new IOException("Context artifact content is missing");
        try {
            String tool = values.getProperty("tool");
            String hash = values.getProperty("sha256");
            String projection = values.getProperty("projection");
            int chars = Integer.parseInt(values.getProperty("chars"));
            if (tool == null || hash == null || !hash.matches("[a-f0-9]{64}")
                    || projection == null || chars < 0) throw new IllegalArgumentException("Incomplete metadata");
            return new Artifact(artifactId, tool, hash, chars, projection, body);
        } catch (RuntimeException e) {
            throw new IOException("Invalid context artifact metadata", e);
        }
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
        return "Historical tool-result snapshot; tool=" + artifact.toolName
                + "; sha256=" + artifact.sha256 + "; total_chars=" + artifact.chars
                + "; offset=" + start + "; next_offset=" + next
                + "; has_more=" + (next < artifact.chars) + "\n" + text;
    }

    private Path sessionDirectory(String sessionId) {
        return root.resolve(digest(sessionId));
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
            Files.move(from, to, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(from, to);
        }
    }

    static int safeEnd(String content, int end) {
        if (end > 0 && Character.isHighSurrogate(content.charAt(end - 1))) return end - 1;
        return end;
    }

    static String digest(String value) {
        try {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(64);
            for (byte b : bytes) result.append(String.format("%02x", b & 0xff));
            return result.toString();
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

        private Artifact(String id, String toolName, String sha256, int chars, String projection, Path body) {
            this.id = id;
            this.toolName = toolName;
            this.sha256 = sha256;
            this.chars = chars;
            this.projection = projection;
            this.body = body;
        }
    }
}
