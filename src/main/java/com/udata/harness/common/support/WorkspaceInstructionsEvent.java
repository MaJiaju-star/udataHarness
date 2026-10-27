package com.udata.harness.common.support;

import org.noear.solon.ai.agent.AbsAgentEvent;
import org.noear.solon.ai.agent.react.ReActTrace;
import java.util.LinkedHashMap;
import java.util.Map;

/** Metadata only: never expose project instruction contents through status events. */
public final class WorkspaceInstructionsEvent extends AbsAgentEvent {
    private final Map<String, Object> status;
    public WorkspaceInstructionsEvent(ReActTrace trace, Map<String, Object> status) {
        super(trace.getRunId(), trace.getAgentName(), trace.getSession());
        this.status = new LinkedHashMap<>(status);
    }
    public Map<String, Object> getStatus() { return status; }
}
