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
import java.util.Map;
import java.util.HashMap;
import java.util.HashSet;
import java.util.UUID;

/** Durable model-facing checkpoint; the original session transcript remains untouched. */
public final class CompactionCheckpointStore {
    private final Path file;
    private final String initialEpoch = "uninitialized:" + UUID.randomUUID();

    public CompactionCheckpointStore(Path sessionDirectory) {
        this.file = sessionDirectory.toAbsolutePath().normalize().resolve("compaction-checkpoint.properties");
    }

    public synchronized Checkpoint load(List<ChatMessage> rawMessages) {
        ContextMessageIds.ensureRaw(file.toString(), rawMessages);
        return checkpoint(read(), rawMessages);
    }

    private Checkpoint checkpoint(Properties values, List<ChatMessage> rawMessages) {
        try {
            int covered = Integer.parseInt(values.getProperty("coveredMessages", "-1"));
            String summary = values.getProperty("summary", "");
            String digest = values.getProperty("prefixSha256", "");
            if (covered < 0 || covered > rawMessages.size() || summary.trim().isEmpty()) return null;
            if ("2".equals(values.getProperty("version"))) {
                if (!values.getProperty("coveredEntryIds", "").equals(ids(rawMessages, covered))
                        || !values.getProperty("semanticPrefixSha256", "").equals(semanticPrefixHash(rawMessages, covered))) return null;
            } else if (!digest.equals(prefixHash(rawMessages, covered))) return null;
            String entryId = values.getProperty("checkpointId", "summary-" + ContextArtifactStore.digest(summary + digest));
            return new Checkpoint(covered, summary, values.getProperty("model", ""), entryId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    public synchronized List<ChatMessage> build(List<ChatMessage> raw) {
        return build(raw, true);
    }

    public synchronized List<ChatMessage> build(List<ChatMessage> raw, boolean includeCheckpoint) {
        ContextMessageIds.ensureRaw(file.toString(), raw);
        Properties values = read();
        Map<String, ChatMessage> projections = new HashMap<>();
        for (ChatMessage source : raw) {
            String id = ContextMessageIds.id(source);
            String json = values.getProperty("projection." + id);
            if (json == null || !messageHash(source).equals(values.getProperty("sourceHash." + id))) continue;
            try {
                ChatMessage target = ChatMessage.fromJson(json);
                if (id.equals(ContextMessageIds.id(target)) && sameEnvelope(source, target)) projections.put(id, target);
            } catch (RuntimeException ignored) { /* Invalid projections never replace the original transcript. */ }
        }
        return ActiveContextView.build(raw, includeCheckpoint ? checkpoint(values, raw) : null, projections);
    }

    public synchronized String epoch() { return read().getProperty("epoch", initialEpoch); }

    public synchronized String observeSignature(String signature) throws IOException {
        return observeSignature(signature, null);
    }

    public synchronized String observeSignature(String signature, List<ChatMessage> raw) throws IOException {
        return observeSignature(signature, raw, null);
    }

    public synchronized String observeSignature(String signature, List<ChatMessage> raw, List<ChatMessage> visible) throws IOException {
        Properties values = read();
        String basis = raw == null ? values.getProperty("rawBasis", "") : rawBasis(raw);
        String previous = values.getProperty("rawBasis", "");
        boolean prefixChanged = !previous.isEmpty() && !(basis.equals(previous) || basis.startsWith(previous + "\n"));
        boolean stateChanged = values.containsKey("viewStateHash") && !values.getProperty("viewStateHash").equals(viewStateHash(values));
        String visibleBasis = visible == null ? values.getProperty("visibleBasis", "") : rawBasis(visible);
        String oldVisible = values.getProperty("visibleBasis", "");
        boolean visibleChanged = !oldVisible.isEmpty() && !(visibleBasis.equals(oldVisible) || visibleBasis.startsWith(oldVisible + "\n"));
        boolean changed = !signature.equals(values.getProperty("signature")) || !values.containsKey("epoch") || prefixChanged || stateChanged || visibleChanged;
        if (changed || !basis.equals(previous) || !visibleBasis.equals(oldVisible)) {
            values.setProperty("signature", signature);
            values.setProperty("rawBasis", basis);
            values.setProperty("visibleBasis", visibleBasis);
            if (changed) advance(values);
            write(values);
        }
        return values.getProperty("epoch");
    }

    /** Save all changed raw-entry representations and their epoch in one atomic commit. */
    public synchronized String project(List<ChatMessage> raw, List<ChatMessage> view, boolean historical) throws IOException {
        ContextMessageIds.ensureRaw(file.toString(), raw);
        Properties values = read();
        Map<String, ChatMessage> originals = new HashMap<>();
        for (ChatMessage message : raw) originals.put(ContextMessageIds.id(message), message);
        boolean changed = historical;
        for (ChatMessage message : view) {
            String id = ContextMessageIds.id(message);
            ChatMessage source = originals.get(id);
            if (source == null || !sameEnvelope(source, message)) continue;
            String key = "projection." + id;
            if (messageHash(source).equals(messageHash(message))) {
                if (values.remove(key) != null) { values.remove("sourceHash." + id); changed = true; }
            } else {
                ContextMessageIds.projection(source, message);
                String json = ChatMessage.toJson(message);
                if (!json.equals(values.getProperty(key))) {
                    values.setProperty(key, json); values.setProperty("sourceHash." + id, messageHash(source)); changed = true;
                }
            }
        }
        if (changed) {
            values.setProperty("rawBasis", rawBasis(raw)); values.setProperty("visibleBasis", rawBasis(view));
            advance(values); write(values);
        }
        return values.getProperty("epoch", initialEpoch);
    }

    public synchronized void save(List<ChatMessage> rawMessages, int covered,
                                  String summary, String model) throws IOException {
        save(rawMessages, covered, summary, model, null);
    }

    public synchronized void save(List<ChatMessage> rawMessages, int covered,
                                  String summary, String model, List<ChatMessage> retainedTail) throws IOException {
        if (covered < 0 || covered > rawMessages.size() || summary == null || summary.trim().isEmpty()) {
            throw new IOException("Invalid compaction checkpoint");
        }
        ContextMessageIds.ensureRaw(file.toString(), rawMessages);
        if (new HashSet<>(java.util.Arrays.asList(ids(rawMessages, rawMessages.size()).split("\\n"))).size() != rawMessages.size()
                && !rawMessages.isEmpty()) throw new IOException("Duplicate raw entry IDs");
        Properties values = read();
        values.setProperty("version", "2");
        values.setProperty("checkpointId", UUID.randomUUID().toString());
        values.setProperty("coveredEntryIds", ids(rawMessages, covered));
        values.setProperty("semanticPrefixSha256", semanticPrefixHash(rawMessages, covered));
        values.setProperty("coveredMessages", Integer.toString(covered));
        values.setProperty("prefixSha256", prefixHash(rawMessages, covered));
        values.setProperty("summary", summary);
        values.setProperty("model", model == null ? "" : model);
        values.setProperty("rawBasis", rawBasis(rawMessages));
        ChatMessage checkpointMessage = ChatMessage.ofUser(summary);
        checkpointMessage.addMetadata(ContextMessageIds.ENTRY_ID, values.getProperty("checkpointId"));
        checkpointMessage.addMetadata(ContextMessageIds.CHECKPOINT, true);
        List<ChatMessage> active = new java.util.ArrayList<>(); active.add(checkpointMessage);
        active.addAll(retainedTail == null ? rawMessages.subList(covered, rawMessages.size()) : retainedTail);
        values.setProperty("visibleBasis", rawBasis(active));
        for (int i = 0; i < covered; i++) {
            String id = ContextMessageIds.id(rawMessages.get(i));
            values.remove("projection." + id); values.remove("sourceHash." + id);
        }
        advance(values);
        write(values);
    }

    public synchronized void clear() throws IOException { Files.deleteIfExists(file); }

    private Properties read() {
        if (!Files.isRegularFile(file)) return new Properties();
        try { return ContextArtifactStore.readProperties(file); }
        catch (IOException | RuntimeException e) { return new Properties(); }
    }

    private static void advance(Properties values) {
        long generation;
        try { generation = Long.parseLong(values.getProperty("generation", "0")) + 1; }
        catch (NumberFormatException e) { generation = 1; }
        String lineage = values.getProperty("lineage", UUID.randomUUID().toString());
        values.setProperty("lineage", lineage); values.setProperty("generation", Long.toString(generation));
        values.setProperty("epoch", lineage + ":" + generation);
    }

    private void write(Properties values) throws IOException {
        values.setProperty("viewStateHash", viewStateHash(values));
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

    private static String ids(List<ChatMessage> messages, int count) {
        StringBuilder ids = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) ids.append('\n');
            ids.append(ContextMessageIds.ensure(messages.get(i)));
        }
        return ids.toString();
    }

    private static String semanticPrefixHash(List<ChatMessage> messages, int count) {
        StringBuilder hashes = new StringBuilder();
        for (int i = 0; i < count; i++) hashes.append(messageHash(messages.get(i))).append('\n');
        return ContextArtifactStore.digest(hashes.toString());
    }

    private static String rawBasis(List<ChatMessage> raw) {
        StringBuilder basis = new StringBuilder();
        for (ChatMessage message : raw) {
            if (basis.length() > 0) basis.append('\n');
            basis.append(ContextMessageIds.ensure(message)).append(':').append(messageHash(message));
        }
        return basis.toString();
    }

    private static String viewStateHash(Properties values) {
        StringBuilder state = new StringBuilder();
        for (String key : new java.util.TreeSet<>(values.stringPropertyNames())) {
            if (key.startsWith("projection.") || key.startsWith("sourceHash.") || key.equals("summary")
                    || key.equals("coveredEntryIds") || key.equals("checkpointId") || key.equals("prefixSha256")
                    || key.equals("semanticPrefixSha256")) state.append(key).append('=').append(values.getProperty(key)).append('\n');
        }
        return ContextArtifactStore.digest(state.toString());
    }

    private static boolean sameEnvelope(ChatMessage source, ChatMessage target) {
        if (!(source instanceof ToolMessage) || !(target instanceof ToolMessage)) return false;
        ToolMessage a = (ToolMessage) source, b = (ToolMessage) target;
        return java.util.Objects.equals(a.getName(), b.getName()) && java.util.Objects.equals(a.getToolCallId(), b.getToolCallId())
                && a.isError() == b.isError() && !a.isReturnDirect() && !b.isReturnDirect()
                && !a.isMultiModal() && !b.isMultiModal() && !a.hasMetadata(org.noear.solon.ai.agent.AgentTrace.META_FIRST);
    }

    static String messageHash(ChatMessage message) {
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("role", message.getRole().name()); body.put("content", message.getContent());
        if (message instanceof AssistantMessage) {
            AssistantMessage assistant = (AssistantMessage) message;
            body.put("thinking", assistant.getThinking()); body.put("blocks", assistant.getBlocks());
            java.util.List<Map<String, Object>> calls = new java.util.ArrayList<>();
            if (assistant.getToolCalls() != null) for (org.noear.solon.ai.chat.tool.ToolCall call : assistant.getToolCalls()) {
                Map<String, Object> wire = new java.util.LinkedHashMap<>();
                wire.put("id", call.getId()); wire.put("name", call.getName()); wire.put("arguments", call.getArguments()); calls.add(wire);
            }
            body.put("calls", calls);
        } else if (message instanceof UserMessage) body.put("blocks", ((UserMessage) message).getBlocks());
        else if (message instanceof ToolMessage) {
            ToolMessage tool = (ToolMessage) message;
            body.put("name", tool.getName()); body.put("callId", tool.getToolCallId());
            body.put("error", tool.isError()); body.put("returnDirect", tool.isReturnDirect()); body.put("blocks", tool.getBlocks());
        }
        return ContextArtifactStore.digest(ONode.serialize(body));
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
        public final String entryId;

        private Checkpoint(int coveredMessages, String summary, String model, String entryId) {
            this.coveredMessages = coveredMessages;
            this.summary = summary;
            this.model = model;
            this.entryId = entryId;
        }
    }
}
