package com.udata.harness.common.context;

import org.noear.solon.ai.agent.AgentTrace;
import org.noear.solon.ai.agent.react.ReActInterceptor;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.task.ToolExchanger;
import org.noear.solon.ai.chat.content.TextBlock;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.tool.ToolResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** No execution caching; sent history changes only at explicit inactive-task boundaries. */
public class ToolResultContextInterceptor implements ReActInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(ToolResultContextInterceptor.class);
    private static final Set<String> TOOLS = Collections.unmodifiableSet(new HashSet<>(Arrays.asList(
            "read", "bash", "grep", "glob", "ls", "webfetch", "websearch", "codesearch", "lsp")));
    private final ContextArtifactStore store;
    private final ContextManagementOptions options;
    private final ContextTaskStore tasks;
    private final Path workspace;
    private final ContextCacheConfiguration cacheConfig;
    private final ContextLifecyclePolicy lifecycle = new ContextLifecyclePolicy();
    private static final String TASK_META = "context_task_id";
    private static final String INVOCATION_META = "context_invocation_id";

    public ToolResultContextInterceptor(ContextArtifactStore store, ContextManagementOptions options) {
        this(store, options, null, null);
    }

    public ToolResultContextInterceptor(ContextArtifactStore store, ContextManagementOptions options,
                                        ContextTaskStore tasks, Path workspace) {
        this(store, options, tasks, workspace, null);
    }

    public ToolResultContextInterceptor(ContextArtifactStore store, ContextManagementOptions options,
                                        ContextTaskStore tasks, Path workspace, ContextCacheConfiguration cacheConfig) {
        this.store = store;
        this.options = options;
        this.tasks = tasks;
        this.workspace = workspace;
        this.cacheConfig = cacheConfig;
    }

    @Override
    public void onToolCallEnd(ReActTrace trace, ToolExchanger exchanger,
                              ChatMessage observation, Throwable error, long durationMs) {
        ToolResult result = exchanger.getToolResult();
        if (error != null || observation == null || trace.getSession() == null
                || trace.getSession().isPending() || exchanger.isReturnDirect()
                || observation.hasMetadata(AgentTrace.META_FIRST)
                || !eligible(exchanger.getToolName(), result)) return;
        String content = result.getContent();
        String wireId = observation instanceof ToolMessage
                ? ((ToolMessage) observation).getToolCallId()
                : exchanger.getCallId();
        if ("read".equals(exchanger.getToolName())) {
            ReadToolProjection plan = ReadToolProjection.choose(trace, exchanger, content, options.readVisibleChars);
            if (plan != null) {
                String projection = projectRead(trace, wireId, content, plan, exchanger.getArgs());
                if (projection != null) exchanger.setResult(projection);
            } else if (content.length() > options.readVisibleChars) {
                String projection = project(trace, wireId, "read", content, "read_visible_limit",
                        Math.min(options.previewChars, Math.max(0, options.readVisibleChars - 400)), exchanger.getArgs());
                if (projection != null && projection.length() <= options.readVisibleChars) exchanger.setResult(projection);
            }
            return;
        }
        String reason = null;
        int preview = options.previewChars;
        if (content.length() > options.singleResultChars) {
            reason = "single_result_limit";
        }
        if (reason == null) return;
        String projection = project(trace, wireId, exchanger.getToolName(), content, reason, preview, exchanger.getArgs());
        if (projection != null) exchanger.setResult(projection);
    }

    /** ActionTask has committed this batch to WorkingMemory, but Reason has not sent it yet. */
    @Override
    public void onActionEnd(ReActTrace trace, Collection<ToolExchanger> toolCalls) {
        if (trace.getSession() == null || trace.getSession().isPending()
                || trace.getWorkingMemory() == null) return;
        AssistantMessage reason = trace.getLastReasonMessage();
        // Text-mode observations have no wire call IDs: keep their batch unchanged.
        if (reason == null || reason.getToolCalls() == null || reason.getToolCalls().isEmpty()) return;
        Set<String> batchIds = new HashSet<>();
        Map<String, ToolExchanger> exchangersByUuid = new HashMap<>();
        for (ToolExchanger exchanger : toolCalls) exchangersByUuid.put(exchanger.getCallId(), exchanger);
        Map<String, ToolExchanger> exchangersById = new HashMap<>();
        for (ToolCall call : reason.getToolCalls()) {
            batchIds.add(call.getId());
            exchangersById.put(call.getId(), exchangersByUuid.get(call.getUuid()));
        }
        List<ChatMessage> messages = trace.getWorkingMemory().getMessages();
        int batchStart = -1;
        for (int i = messages.size() - 1; i >= 0; i--) {
            if (messages.get(i) == reason) {
                batchStart = i + 1;
                break;
            }
        }
        // A wire ID alone is insufficient: compatible providers may reuse it in older history.
        if (batchStart < 0) return;
        List<ToolMessage> candidates = new ArrayList<>();
        long totalChars = 0;
        for (ChatMessage message : messages.subList(batchStart, messages.size())) {
            if (!(message instanceof ToolMessage)) continue;
            ToolMessage tool = (ToolMessage) message;
            if (!batchIds.contains(tool.getToolCallId())) continue;
            if (tool.getContent() != null) totalChars += tool.getContent().length();
            if (!tool.isReturnDirect() && !tool.isError() && !tool.hasMetadata(AgentTrace.META_FIRST)
                    && TOOLS.contains(tool.getName()) && !tool.isMultiModal()
                    && tool.getBlocks().stream().allMatch(block -> block instanceof TextBlock)
                    && isRawText(tool.getContent())) candidates.add(tool);
        }
        if (totalChars <= options.batchResultChars) {
            convergeTasks(trace, batchStart, toolCalls);
            return;
        }
        candidates.sort(Comparator.comparingInt((ToolMessage value) -> value.getContent().length()).reversed());
        Map<String, ToolMessage> replacements = new HashMap<>();
        for (ToolMessage tool : candidates) {
            if (totalChars <= options.batchResultChars) break;
            ToolExchanger source = exchangersById.get(tool.getToolCallId());
            String projection = project(trace, tool.getToolCallId(), tool.getName(), tool.getContent(),
                    "batch_result_limit", options.previewChars, source == null ? null : source.getArgs());
            if (projection == null) continue;
            ToolResult replacementResult = ToolResult.success(projection);
            if (tool.getMetadata() != null) tool.getMetadata().forEach((key, value) -> {
                if (!"token_size".equals(key)) replacementResult.metas().put(key, value);
            });
            ToolMessage replacement = ChatMessage.ofTool(replacementResult, tool.getName(), tool.getToolCallId(), false);
            if (tool.getMetadata() != null) {
                tool.getMetadata().forEach((key, value) -> {
                    if (!"token_size".equals(key)) replacement.addMetadata(key, value);
                });
            }
            replacements.put(tool.getToolCallId(), replacement);
            ToolExchanger exchanger = exchangersById.get(tool.getToolCallId());
            if (exchanger != null) exchanger.setResult(projection);
            totalChars -= tool.getContent().length() - projection.length();
        }
        if (!replacements.isEmpty()) {
            List<ChatMessage> projected = new ArrayList<>(messages.size());
            for (int i = 0; i < messages.size(); i++) {
                ChatMessage message = messages.get(i);
                ToolMessage replacement = i >= batchStart && message instanceof ToolMessage
                        ? replacements.get(((ToolMessage) message).getToolCallId()) : null;
                projected.add(replacement == null ? message : replacement);
            }
            trace.getWorkingMemory().replaceMessages(projected);
        }
        // Non-reducible results may exceed the soft budget. Existing compression remains the safety net.
        convergeTasks(trace, batchStart, toolCalls);
    }

    /** Explicit task boundaries may rewrite old tool bodies; ordinary rounds keep sent history frozen. */
    private void convergeTasks(ReActTrace trace, int batchStart, Collection<ToolExchanger> calls) {
        if (tasks == null) return;
        try {
            String sessionId = trace.getSession().getSessionId();
            String active = tasks.currentTaskId(sessionId);
            List<ChatMessage> messages = trace.getWorkingMemory().getMessages();
            boolean mixedSwitch = calls.stream().anyMatch(call -> "context_task_update".equals(call.getToolName()));
            List<ChatMessage> view = new ArrayList<>(messages);
            Map<String, String> states = new HashMap<>();
            List<ArchivePlan> plans = new ArrayList<>();
            int appliedCount = 0;
            boolean changed = false;
            for (int i = 0; i < messages.size(); i++) {
                if (!(messages.get(i) instanceof ToolMessage)) continue;
                ToolMessage tool = (ToolMessage) messages.get(i);
                if (tool.isError() || tool.isReturnDirect() || tool.hasMetadata(AgentTrace.META_FIRST)
                        || tool.isMultiModal() || !TOOLS.contains(tool.getName())
                        || !tool.getBlocks().stream().allMatch(block -> block instanceof TextBlock)) continue;
                if (i >= batchStart) {
                    // Concurrent task switches and reads have ambiguous ownership: do not guess.
                    if (!mixedSwitch && !active.isEmpty()) {
                        tool.addMetadata(TASK_META, active);
                        tool.addMetadata(INVOCATION_META, trace.getRunId() + ":" + trace.getCurrentTurnId() + ":" + tool.getToolCallId());
                        if ("read".equals(tool.getName()) && isRawText(tool.getContent()) && tool.getContent().length() > 1000) {
                            Map<String, String> source = new HashMap<>();
                            for (ToolCall wire : trace.getLastReasonMessage().getToolCalls()) {
                                if (!tool.getToolCallId().equals(wire.getId())) continue;
                                for (ToolExchanger call : calls) {
                                    if (wire.getUuid().equals(call.getCallId())) ContextSourceVersion.capture(workspace, call.getArgs(), source);
                                }
                            }
                            source.forEach((key, value) -> tool.addMetadata("context_" + key, value));
                        }
                    }
                    continue;
                }
                if (!isRawText(tool.getContent()) || tool.getMetadata() == null) continue;
                Object owner = tool.getMetadata().get(TASK_META);
                Object invocation = tool.getMetadata().get(INVOCATION_META);
                if (!(owner instanceof String) || !(invocation instanceof String)) continue;
                String taskId = (String) owner;
                if (!states.containsKey(taskId)) states.put(taskId, tasks.state(sessionId, taskId));
                String state = states.get(taskId);
                if (!lifecycle.shouldArchive(taskId, active, state, tool.getContent().length())) continue;
                Map<String, String> metadata = new HashMap<>();
                metadata.put("taskId", taskId);
                for (String key : new String[]{"sourcePath", "sourceHash", "sourceRange"}) {
                    Object value = tool.getMetadata().get("context_" + key);
                    if (value instanceof String) metadata.put(key, (String) value);
                }
                String reason = "task_" + state;
                String preview = ContextArtifactStore.preview(invocation.toString(), tool.getContent(), reason, Math.min(options.previewChars, 400));
                if (preview.length() < tool.getContent().length()
                        && org.noear.solon.ai.agent.react.intercept.compress.CompressionUtil.countTokens(preview)
                        < org.noear.solon.ai.agent.react.intercept.compress.CompressionUtil.countTokens(tool.getContent()))
                    plans.add(new ArchivePlan(i, tool, invocation.toString(), reason, metadata, preview));
            }
            if (plans.isEmpty() || !approveRewrite(trace, messages, plans, active)) return;
            for (ArchivePlan plan : plans) {
                ToolMessage tool = plan.tool;
                ContextArtifactStore.Artifact artifact = store.save(sessionId, plan.invocation, tool.getName(),
                        tool.getContent(), plan.reason, Math.min(options.previewChars, 400), plan.metadata);
                if (artifact.projection.length() >= tool.getContent().length()) continue;
                ToolMessage replacement = ChatMessage.ofTool(ToolResult.success(artifact.projection),
                        tool.getName(), tool.getToolCallId(), false);
                tool.getMetadata().forEach((key, value) -> {
                    if (!"token_size".equals(key)) replacement.addMetadata(key, value);
                });
                view.set(plan.index, replacement);
                appliedCount++;
                changed = true;
            }
            if (changed) {
                long beforeChars = messages.stream().mapToLong(message -> message.getContent() == null ? 0 : message.getContent().length()).sum();
                trace.getWorkingMemory().replaceMessages(view);
                Map<String, Object> applied = new java.util.LinkedHashMap<>();
                applied.put("timestamp", System.currentTimeMillis()); applied.put("action", "context_rewrite");
                applied.put("reason", "task_lifecycle"); applied.put("runId", trace.getRunId());
                applied.put("reasonId", trace.getCurrentTurnId()); applied.put("appliedCount", appliedCount);
                applied.put("charsBefore", beforeChars);
                applied.put("charsAfter", view.stream().mapToLong(message -> message.getContent() == null ? 0 : message.getContent().length()).sum());
                store.recordDecision(sessionId, applied);
            }
        } catch (IOException e) {
            LOG.warn("Task context convergence failed; retaining original history ({})", e.getClass().getSimpleName());
            Map<String, Object> failed = new java.util.LinkedHashMap<>();
            failed.put("timestamp", System.currentTimeMillis()); failed.put("action", "rewrite_failed");
            failed.put("reason", "artifact_io"); failed.put("runId", trace.getRunId()); failed.put("reasonId", trace.getCurrentTurnId());
            store.recordDecision(trace.getSession().getSessionId(), failed);
        }
    }

    private boolean approveRewrite(ReActTrace trace, List<ChatMessage> messages, List<ArchivePlan> plans, String active) {
        if (cacheConfig == null) return true;
        String model = ContextTelemetryInterceptor.model(trace);
        ContextCostPolicy policy = cacheConfig.policy(model);
        long before = ((Number) ContextTelemetryInterceptor.footprint(messages, "", trace.getOptions().getTools(),
                trace.getProtocolTools(), model).view.get("estimatedTokens")).longValue();
        ContextTelemetryInterceptor.Footprint previous = trace.getExtraAs(ContextTelemetryInterceptor.SNAPSHOT);
        if (previous != null) {
            Map<String, Number> categories = (Map<String, Number>) previous.view.get("categories");
            before += categories.get("system").longValue();
        }
        long saved = 0;
        for (ArchivePlan plan : plans) {
            saved += org.noear.solon.ai.agent.react.intercept.compress.CompressionUtil.countTokens(plan.tool.getContent())
                    - org.noear.solon.ai.agent.react.intercept.compress.CompressionUtil.countTokens(plan.preview);
        }
        Map<String, Object> usage = trace.getExtraAs(ContextTelemetryInterceptor.LAST_USAGE);
        if (usage != null && !model.equals(usage.get("model"))) usage = null;
        Map<String, Object> decision = policy.decide(before, saved,
                trace.getOptions().getChatModel().getConfig().getContextLength(), usage);
        decision.put("timestamp", System.currentTimeMillis()); decision.put("action", "cache_decision");
        decision.put("runId", trace.getRunId()); decision.put("reasonId", trace.getCurrentTurnId());
        decision.put("model", model); decision.put("activeTaskId", active); decision.put("candidateCount", plans.size());
        store.recordDecision(trace.getSession().getSessionId(), decision);
        return Boolean.TRUE.equals(decision.get("allow"));
    }

    private static final class ArchivePlan {
        final int index;
        final ToolMessage tool;
        final String invocation, reason, preview;
        final Map<String, String> metadata;
        ArchivePlan(int index, ToolMessage tool, String invocation, String reason, Map<String, String> metadata, String preview) {
            this.index = index; this.tool = tool; this.invocation = invocation; this.reason = reason;
            this.metadata = metadata; this.preview = preview;
        }
    }

    private String project(ReActTrace trace, String invocationId, String tool, String content,
                           String reason, int previewChars, Map<String, Object> args) {
        try {
            // Some compatible providers reuse wire call IDs across turns. Scope by run and Reason.
            String scopedInvocation = trace.getRunId() + ":" + trace.getCurrentTurnId() + ":" + invocationId + ":" + reason;
            String sessionId = trace.getSession().getSessionId();
            Map<String, String> metadata = new HashMap<>();
            if (tasks != null) {
                try { metadata.put("taskId", tasks.currentTaskId(sessionId)); }
                catch (IOException ignored) { /* Task bookkeeping must not disable result governance. */ }
            }
            if ("read".equals(tool)) ContextSourceVersion.capture(workspace, args, metadata);
            ContextArtifactStore.Artifact artifact = tasks == null && workspace == null
                    ? store.save(sessionId, scopedInvocation, tool, content, reason, previewChars)
                    : store.save(sessionId, scopedInvocation, tool, content, reason, previewChars, metadata);
            return artifact.projection.length() < content.length() ? artifact.projection : null;
        } catch (IOException e) {
            // Do not log payloads, arguments, or filesystem error messages containing user data.
            LOG.warn("Context artifact persistence failed; retaining tool result ({})", e.getClass().getSimpleName());
            return null;
        }
    }

    private String projectRead(ReActTrace trace, String invocationId, String content,
                               ReadToolProjection plan, Map<String, Object> args) {
        try {
            String scopedInvocation = trace.getRunId() + ":" + trace.getCurrentTurnId() + ":" + invocationId + ":read_projection";
            String sessionId = trace.getSession().getSessionId();
            Map<String, String> metadata = new HashMap<>();
            if (tasks != null) {
                try { metadata.put("taskId", tasks.currentTaskId(sessionId)); }
                catch (IOException ignored) { /* Projection must not depend on task bookkeeping. */ }
            }
            ContextSourceVersion.capture(workspace, args, metadata);
            ContextArtifactStore.Artifact artifact = store.save(sessionId, scopedInvocation,
                    "read", content, "read_" + plan.type, 0, metadata);
            String projected = plan.render(artifact.id);
            return projected.length() <= options.readVisibleChars && projected.length() < content.length()
                    ? projected : null;
        } catch (IOException e) {
            LOG.warn("Read projection persistence failed; retaining original ({})", e.getClass().getSimpleName());
            return null;
        }
    }

    private static boolean eligible(String tool, ToolResult result) {
        return TOOLS.contains(tool) && result != null && !result.isError()
                && result.getBlocks().stream().allMatch(block -> block instanceof TextBlock)
                && isRawText(result.getContent());
    }

    private static boolean isRawText(String content) {
        return content != null && !content.isEmpty() && !content.startsWith("<context-artifact id=\"");
    }

}
