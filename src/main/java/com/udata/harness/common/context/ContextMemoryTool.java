package com.udata.harness.common.context;

import org.noear.solon.ai.annotation.ToolMapping;
import org.noear.solon.ai.chat.tool.AbsToolProvider;
import org.noear.solon.annotation.Param;
import java.io.IOException;

/** Session scope is injected by Harness, never supplied by the model. */
public final class ContextMemoryTool extends AbsToolProvider {
    private final ContextArtifactStore artifacts;
    private final ContextTaskStore tasks;

    public ContextMemoryTool(ContextArtifactStore artifacts, ContextTaskStore tasks) {
        this.artifacts = artifacts;
        this.tasks = tasks;
    }

    @ToolMapping(name = "context_search", description = "Find historical archived tool results in this session when artifact IDs are lost. Literal case-sensitive search returns artifact IDs and UTF-16 offsets for context_restore. Bounded search reports truncation and next_offset. Historical evidence may need revalidation.")
    public String search(@Param("query") String query,
                         @Param(value = "task_id", required = false) String taskId,
                         @Param(value = "source_path", required = false) String sourcePath,
                         @Param(value = "offset", required = false, defaultValue = "0") Integer offset,
                         @Param(value = "limit", required = false, defaultValue = "5") Integer limit,
                         @Param(value = "body_offset", required = false, defaultValue = "0") Integer bodyOffset,
                         String __sessionId) {
        try { return artifacts.search(__sessionId, query, taskId, sourcePath,
                offset == null ? 0 : offset, limit == null ? 5 : limit, bodyOffset == null ? 0 : bodyOffset); }
        catch (IOException e) { return "Context search unavailable or invalid arguments in this session."; }
    }

    @ToolMapping(name = "context_task_update", description = "Record task focus before substantial work or switching modules. Set active, paused or closed; activating another task pauses the previous one. Preserve user constraints and unresolved dependencies in requirements. Save conclusions, verification evidence and next steps before leaving a task. Closing requires conclusion and reported verification; this tool does not run tests or prove success. Omitted fields preserve prior values. IDs use letters, digits, underscore or hyphen, max 64.")
    public String update(@Param("task_id") String taskId,
                         @Param("state") String state,
                         @Param(value = "module", required = false) String module,
                         @Param(value = "requirements", required = false) String requirements,
                         @Param(value = "conclusion", required = false) String conclusion,
                         @Param(value = "verification", required = false) String verification,
                         @Param(value = "next_step", required = false) String nextStep,
                         String __sessionId) {
        try { return tasks.update(__sessionId, taskId, module, state, requirements, conclusion, verification, nextStep); }
        catch (IOException e) { return "Task update rejected or unavailable. Use a valid ID/state, fields <=4000 chars, and conclusion plus verification when closing."; }
    }

    @ToolMapping(name = "context_task_snapshot", description = "Recover persisted task requirements, conclusions, reported verification and next steps after a context reset or when revisiting a task. Omit task_id for current active task. This does not restore source code or validate evidence; use context_search/context_restore or re-read current files.")
    public String snapshot(@Param(value = "task_id", required = false) String taskId, String __sessionId) {
        try { return tasks.snapshot(__sessionId, taskId); }
        catch (IOException e) { return "Task snapshot unavailable in this session."; }
    }
}
