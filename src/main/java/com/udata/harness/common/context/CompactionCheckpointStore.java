package com.udata.harness.common.context;

import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.UserMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.snack4.ONode;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Properties;

/** Durable model-facing checkpoint; the original session transcript remains untouched. */
public final class CompactionCheckpointStore {
    private final Path file;

    public CompactionCheckpointStore(Path sessionDirectory) {
        this.file = sessionDirectory.toAbsolutePath().normalize().resolve("compaction-checkpoint.properties");
    }

    public synchronized Checkpoint load(List<ChatMessage> rawMessages) {
        if (!Files.isRegularFile(file)) return null;
        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            values.load(input);
            int covered = Integer.parseInt(values.getProperty("coveredMessages", "-1"));
            String summary = values.getProperty("summary", "");
            String digest = values.getProperty("prefixSha256", "");
            if (covered < 0 || covered > rawMessages.size() || summary.trim().isEmpty()
                    || !digest.equals(prefixHash(rawMessages, covered))) return null;
            return new Checkpoint(covered, summary, values.getProperty("model", ""));
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    public synchronized void save(List<ChatMessage> rawMessages, int covered,
                                  String summary, String model) throws IOException {
        if (covered < 0 || covered > rawMessages.size() || summary == null || summary.trim().isEmpty()) {
            throw new IOException("Invalid compaction checkpoint");
        }
        Properties values = new Properties();
        values.setProperty("coveredMessages", Integer.toString(covered));
        values.setProperty("prefixSha256", prefixHash(rawMessages, covered));
        values.setProperty("summary", summary);
        values.setProperty("model", model == null ? "" : model);
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "compaction-", ".tmp");
        try {
            try (OutputStream output = Files.newOutputStream(temporary)) {
                values.store(output, "Model-facing compaction checkpoint");
            }
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static String prefixHash(List<ChatMessage> messages, int count) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (int i = 0; i < count; i++) {
                ChatMessage message = messages.get(i);
                update(digest, message.getRole().name());
                update(digest, message.getContent());
                if (message instanceof AssistantMessage) {
                    AssistantMessage assistant = (AssistantMessage) message;
                    update(digest, ONode.serialize(assistant.getBlocks()));
                    update(digest, ONode.serialize(assistant.getToolCalls()));
                } else if (message instanceof UserMessage) {
                    update(digest, ONode.serialize(((UserMessage) message).getBlocks()));
                } else if (message instanceof ToolMessage) {
                    update(digest, ONode.serialize(((ToolMessage) message).getBlocks()));
                }
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte value : digest.digest()) hex.append(String.format("%02x", value & 0xff));
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = (value == null ? "" : value).getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    public static final class Checkpoint {
        public final int coveredMessages;
        public final String summary;
        public final String model;

        private Checkpoint(int coveredMessages, String summary, String model) {
            this.coveredMessages = coveredMessages;
            this.summary = summary;
            this.model = model;
        }
    }
}
