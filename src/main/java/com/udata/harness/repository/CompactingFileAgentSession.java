package com.udata.harness.repository;

import com.udata.harness.common.context.CompactionCheckpointStore;
import com.udata.harness.common.context.ContextMessageIds;
import org.noear.solon.ai.agent.session.FileAgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;

import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** Projects a durable checkpoint into model history while retaining raw UI history. */
public final class CompactingFileAgentSession extends FileAgentSession {
    private final CompactionCheckpointStore checkpoints;
    private final boolean checkpointEnabled;

    public CompactingFileAgentSession(String sessionId, String directory) {
        this(sessionId, directory, true);
    }

    public CompactingFileAgentSession(String sessionId, String directory, boolean checkpointEnabled) {
        super(sessionId, directory);
        this.checkpointEnabled = checkpointEnabled;
        checkpoints = new CompactionCheckpointStore(Paths.get(directory));
    }

    public CompactionCheckpointStore checkpoints() {
        return checkpoints;
    }

    public boolean checkpointEnabled() { return checkpointEnabled; }

    @Override
    public List<ChatMessage> getLatestMessages(int windowSize) {
        List<ChatMessage> view = checkpoints.build(getMessages(), checkpointEnabled);
        if (!checkpointEnabled && windowSize > 0 && view.size() > windowSize)
            return new ArrayList<>(view.subList(view.size() - windowSize, view.size()));
        return view;
    }

    @Override
    public List<ChatMessage> getMessages() {
        List<ChatMessage> raw = super.getMessages();
        ContextMessageIds.ensureRaw(getSessionId(), raw);
        return raw;
    }

    @Override
    public void addMessage(java.util.Collection<? extends ChatMessage> messages) {
        for (ChatMessage message : messages) ContextMessageIds.ensure(message);
        super.addMessage(messages);
    }

    @Override
    public void clear() {
        super.clear();
        try { checkpoints.clear(); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot clear context state", e); }
    }
}
