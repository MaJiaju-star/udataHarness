package com.udata.harness.common.context;

import org.noear.solon.ai.chat.message.ChatMessage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** The one model-history builder; the transcript remains the source of truth. */
public final class ActiveContextView {
    private ActiveContextView() { }

    static List<ChatMessage> build(List<ChatMessage> raw, CompactionCheckpointStore.Checkpoint checkpoint,
                                   Map<String, ChatMessage> projections) {
        List<ChatMessage> view = new ArrayList<>();
        int covered = 0;
        if (checkpoint != null) {
            ChatMessage summary = ChatMessage.ofUser(checkpoint.summary);
            summary.addMetadata(ContextMessageIds.ENTRY_ID, checkpoint.entryId);
            summary.addMetadata(ContextMessageIds.CHECKPOINT, true);
            view.add(summary);
            covered = checkpoint.coveredMessages;
        }
        for (int i = covered; i < raw.size(); i++) {
            ChatMessage message = raw.get(i);
            ChatMessage projected = projections.get(ContextMessageIds.id(message));
            view.add(projected == null ? message : projected);
        }
        return view;
    }

    /** Transient tool entries do not advance the persisted transcript boundary. Known raw IDs must stay ordered. */
    public static int coveredThrough(List<ChatMessage> raw, int alreadyCovered, List<ChatMessage> prefix) {
        Map<String, Integer> positions = new java.util.HashMap<>();
        for (int i = 0; i < raw.size(); i++) positions.put(ContextMessageIds.ensure(raw.get(i)), i);
        int covered = alreadyCovered;
        for (ChatMessage message : prefix) {
            Integer index = positions.get(ContextMessageIds.id(message));
            if (index == null) continue;
            if (index < alreadyCovered) throw new IllegalArgumentException("Covered history re-entered active context");
            if (index != covered) throw new IllegalArgumentException("Non-contiguous compaction boundary");
            covered++;
        }
        return covered;
    }
}
