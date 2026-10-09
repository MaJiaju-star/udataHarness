package com.udata.harness.common.context;

import com.udata.harness.repository.ModelContextAgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.chat.message.ChatMessage;
import java.util.*;

/** Optimistic guard against stale summaries. All checks precede durable replacement. */
public final class SummaryCommitGuard {
    private final ReActTrace trace;
    private final ModelContextAgentSession session;
    private final List<ChatMessage> memory;
    private final long revision;
    private final int archiveSize;
    private final long taskRevision;
    public SummaryCommitGuard(ReActTrace trace) {
        this.trace = trace; session = (ModelContextAgentSession) trace.getSession();
        memory = TokenPressureMeter.copy(trace.getWorkingMemory().getMessages());
        revision = session.contextRevision(); archiveSize = session.getMessages().size();
        taskRevision = ((Number) session.taskState().get().getOrDefault("revision", 0L)).longValue();
    }
    public boolean unchanged() {
        return revision == session.contextRevision() && archiveSize == session.getMessages().size()
                && taskRevision == ((Number) session.taskState().get().getOrDefault("revision", 0L)).longValue()
                && TokenPressureMeter.sameMessages(memory, trace.getWorkingMemory().getMessages());
    }
    public void check() {
        if (Thread.currentThread().isInterrupted() || (trace.hasStreamSink() && trace.isStreamCancelled()))
            throw new IllegalStateException("Summary cancelled before commit");
        if (!unchanged()) throw new IllegalStateException("Summary source changed; stale checkpoint rejected");
    }
    public long revision() { return revision; }
    public int archiveSize() { return archiveSize; }
    public static void shrinking(List<ChatMessage> old, ChatMessage summary) {
        ContextProtection.validateSummary(summary);
        if (TokenPressureMeter.messagesTokens(Collections.singletonList(summary)) >= TokenPressureMeter.messagesTokens(old))
            throw new IllegalStateException("Framed summary does not reduce context tokens");
    }
}
