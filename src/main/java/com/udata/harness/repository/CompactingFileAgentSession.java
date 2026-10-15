package com.udata.harness.repository;

import com.udata.harness.common.context.CompactionCheckpointStore;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Projects a durable checkpoint into model history while retaining raw UI history. */
public final class CompactingFileAgentSession extends FileAgentSession {
    private final CompactionCheckpointStore checkpoints;

    public CompactingFileAgentSession(String sessionId, String directory) {
        super(sessionId, directory);
        checkpoints = new CompactionCheckpointStore(Paths.get(directory));
    }

    public CompactionCheckpointStore checkpoints() {
        return checkpoints;
    }

    @Override
    public List<ChatMessage> getLatestMessages(int windowSize) {
        List<ChatMessage> raw = super.getMessages();
        CompactionCheckpointStore.Checkpoint checkpoint = checkpoints.load(raw);
        if (checkpoint == null) return new ArrayList<>(raw);
        List<ChatMessage> projected = new ArrayList<>();
        projected.add(ChatMessage.ofUser(checkpoint.summary));
        projected.addAll(raw.subList(checkpoint.coveredMessages, raw.size()));
        return projected;
    }
}
