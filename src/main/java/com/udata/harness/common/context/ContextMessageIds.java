package com.udata.harness.common.context;

import org.noear.solon.ai.chat.message.ChatMessage;
import java.util.List;
import java.util.UUID;

/** Application metadata only: IDs are never inserted into provider message content. */
public final class ContextMessageIds {
    public static final String ENTRY_ID = "udata_entry_id";
    public static final String SOURCE_ID = "udata_source_entry_id";
    public static final String CHECKPOINT = "udata_checkpoint";
    public static final String INTERNAL_CONTEXT = "udata_internal_context";

    private ContextMessageIds() { }

    public static String id(ChatMessage message) {
        Object value = message.getMetadata() == null ? null : message.getMetadata().get(ENTRY_ID);
        return value instanceof String && ((String) value).matches("[A-Za-z0-9-]{1,100}") ? (String) value : null;
    }

    public static String ensure(ChatMessage message) {
        String id = id(message);
        if (id == null) { id = UUID.randomUUID().toString(); message.addMetadata(ENTRY_ID, id); }
        return id;
    }

    /** Old transcripts acquire repeatable IDs without rewriting their bodies or depending on object identity. */
    public static void ensureRaw(String sessionId, List<ChatMessage> messages) {
        for (int i = 0; i < messages.size(); i++) {
            ChatMessage message = messages.get(i);
            if (id(message) == null) {
                message.addMetadata(ENTRY_ID, "legacy-" + ContextArtifactStore.digest(sessionId + ":" + i + ":"
                        + CompactionCheckpointStore.messageHash(message)));
            }
        }
    }

    public static void projection(ChatMessage source, ChatMessage target) {
        String id = ensure(source);
        target.addMetadata(ENTRY_ID, id);
        target.addMetadata(SOURCE_ID, id);
    }

    public static boolean checkpoint(ChatMessage message) {
        return Boolean.TRUE.equals(message.getMetadataAs(CHECKPOINT));
    }

    public static boolean internal(ChatMessage message) {
        return message instanceof org.noear.solon.ai.chat.message.UserMessage
                && (Boolean.TRUE.equals(message.getMetadataAs(INTERNAL_CONTEXT))
                    // Compatibility with project messages persisted before the shared marker existed.
                    || Boolean.TRUE.equals(message.getMetadataAs("udata_workspace_instruction")));
    }
}
