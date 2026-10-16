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

/**
 * 管控工具结果如何投影（裁剪/摘要）后再进入已发送历史的 ReAct 拦截器。
 *
 * <p>它在两个时点进行拦截：</p>
 * <ul>
 * <li>{@link #onToolCallEnd} —— 单结果投影：超大结果、{@code bash} 与
 * {@code read} 的输出按各自工具预算裁剪；全文持久化为 artifact，
 * 历史中只接收引用它的简短占位（stub）。</li>
 * <li>{@link #onActionEnd} —— 批级投影：当已提交批次超过软预算时，
 * 优先裁剪最大的可投影纯文本结果；随后在显式任务边界处归档并改写较早的工具结果正文（见
 * {@code convergeTasks}）。</li>
 * </ul>
 *
 * <p>不变量：不做执行缓存——结果绝不会从缓存重复供给；普通轮次内已发送历史保持冻结，
 * 仅在显式的非活跃任务边界处变更。持久化失败只记录日志（不含负载内容），
 * 且绝不替换原始工具结果。</p>
 */
public class ToolResultContextInterceptor implements ReActInterceptor {
    private static final Logger LOG = LoggerFactory.getLogger(ToolResultContextInterceptor.class);
    /** 其纯文本结果可参与投影的工具；其余工具原样放行。 */
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

    /**
 * 创建不带任务跟踪、源码版本捕获与缓存成本门控的拦截器。
 *
 * @param store   制品库：持久化完整工具输出与决策记录
 * @param options 投影预算（可见字符数、预览大小、批次上限）
 */
    public ToolResultContextInterceptor(ContextArtifactStore store, ContextManagementOptions options) {
        this(store, options, null, null);
    }

    /**
 * 创建启用任务边界归档的拦截器。
 *
 * @param store     制品库：持久化完整工具输出与决策记录
 * @param options   投影预算（可见字符数、预览大小、批次上限）
 * @param tasks     任务库：将结果归属到任务，并按生命周期状态驱动归档；
 *                  传 {@code null} 则禁用任务收敛
 * @param workspace 工作区根目录，用于捕获 {@code read} 的源码版本；
 *                  传 {@code null} 则禁用源码版本捕获
 */
    public ToolResultContextInterceptor(ContextArtifactStore store, ContextManagementOptions options,
                                        ContextTaskStore tasks, Path workspace) {
        this(store, options, tasks, workspace, null);
    }

    /**
 * 全参构造；额外通过所配置的成本策略对任务边界改写进行门控。
 *
 * @param store       制品库：持久化完整工具输出与决策记录
 * @param options     投影预算（可见字符数、预览大小、批次上限）
 * @param tasks       任务库：将结果归属到任务，并按生命周期状态驱动归档；
 *                    传 {@code null} 则禁用任务收敛
 * @param workspace   工作区根目录，用于捕获 {@code read} 的源码版本；
 *                    传 {@code null} 则禁用源码版本捕获
 * @param cacheConfig 缓存配置：提供按模型区分的成本策略；
 *                    传 {@code null} 则无条件批准改写
 */
    public ToolResultContextInterceptor(ContextArtifactStore store, ContextManagementOptions options,
                                        ContextTaskStore tasks, Path workspace, ContextCacheConfiguration cacheConfig) {
        this.store = store;
        this.options = options;
        this.tasks = tasks;
        this.workspace = workspace;
        this.cacheConfig = cacheConfig;
    }

    /**
 * 在单个工具结果提交到工作记忆之前，就地对其投影。
 *
 * <p>以下情况保持原样、直接返回：执行抛出异常、observation 或 session 缺失、
 * session 处于 pending、结果为直接返回（return-direct）、携带首次观测标记
 * （{@code META_FIRST}），或不是纯文本。错误结果按
 * {@link ContextManagementOptions#errorVisibleChars} 预算裁剪；{@code bash} 还遵循
 * 正值的 {@code max_output_chars} 参数（下限 600 字符，并受配置预算封顶），对照
 * {@link ContextManagementOptions#bashVisibleChars} 裁剪；
 * {@code read} 由 {@link ReadToolProjection} 处理（重复引用、同版本增量
 * 或受限摘录），并有通用截断兜底；其余白名单工具仅在超过
 * {@link ContextManagementOptions#singleResultChars} 时才被裁剪。</p>
 *
 * @param trace       当前 ReAct 轨迹（其 session 必须存在）
 * @param exchanger   已完成的交换；其结果可被就地替换
 * @param observation 将回传给模型的观测消息
 * @param error       执行错误（如有）—— 只要非空即提前返回
 * @param durationMs  执行耗时（毫秒，此处未使用）
 */
    @Override
    public void onToolCallEnd(ReActTrace trace, ToolExchanger exchanger,
                              ChatMessage observation, Throwable error, long durationMs) {
        ToolResult result = exchanger.getToolResult();
        if (error != null || observation == null || trace.getSession() == null
                || trace.getSession().isPending() || exchanger.isReturnDirect()
                || observation.hasMetadata(AgentTrace.META_FIRST)
                || !textOnly(result)) return;
        String content = result.getContent();
        String wireId = observation instanceof ToolMessage
                ? ((ToolMessage) observation).getToolCallId()
                : exchanger.getCallId();
        if (result.isError()) {
            ToolOutcomeProjection plan = ToolOutcomeProjection.plan(
                    exchanger.getToolName(), content, true, options.errorVisibleChars);
            if (plan != null) {
                String projection = projectOutcome(trace, wireId, exchanger.getToolName(), content, plan,
                        options.errorVisibleChars, exchanger.getArgs());
                if (projection != null) exchanger.setResult(projection);
            }
            return;
        }
        if (error != null || !TOOLS.contains(exchanger.getToolName())) return;
        if ("bash".equals(exchanger.getToolName())) {
            int visible = options.bashVisibleChars;
            Object requested = exchanger.getArgs() == null ? null : exchanger.getArgs().get("max_output_chars");
            if (requested instanceof Number && ((Number) requested).intValue() > 0) {
                visible = Math.min(visible, Math.max(600, ((Number) requested).intValue()));
            }
            ToolOutcomeProjection plan = ToolOutcomeProjection.plan("bash", content, false, visible);
            if (plan != null) {
                String projection = projectOutcome(trace, wireId, "bash", content, plan, visible,
                        exchanger.getArgs());
                if (projection != null) exchanger.setResult(projection);
                return;
            }
        }
        if ("read".equals(exchanger.getToolName())) {
            if (content.startsWith("<read-unchanged>")) return;
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

    /**
 * 在 ActionTask 将批次提交到工作记忆之后、Reason 发送之前，对观测批次进行投影。
 *
 * <p>先重新投影已提交的错误结果（见 {@code projectCommittedErrors}）；随后——当批次总量超过
 * {@link ContextManagementOptions#batchResultChars} 时——按从大到小的顺序裁剪可投影的纯文本结果，
 * 直到满足软预算。最后 {@code convergeTasks} 把新结果归属到活跃任务，
 * 并归档已结束任务持有的正文。</p>
 *
 * @param trace     当前 ReAct 轨迹
 * @param toolCalls 本动作批次中执行的交换
 */
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
        if (projectCommittedErrors(trace, messages, batchStart, batchIds, exchangersById)) {
            messages = trace.getWorkingMemory().getMessages();
        }
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
        // A complete source read is costly to recover and often needed for edits.
        // When a batch is over budget, shrink other tool results first.
        candidates.sort((left, right) -> {
            int readPriority = Boolean.compare("read".equals(left.getName()), "read".equals(right.getName()));
            return readPriority != 0 ? readPriority
                    : Integer.compare(right.getContent().length(), left.getContent().length());
        });
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

    /**
 * 把新增的工具结果归属到活跃任务，并归档已到达显式生命周期边界之任务持有的正文。
 *
 * <p>普通轮次内已发送历史保持冻结：只有当生命周期策略判定其所属任务已关闭或暂停
 * （绝不会是活跃任务）、该替换确实能缩短文本并节省 token、且成本策略批准此次改写时，
 * 才会把存储的正文换成其 artifact 预览。与 {@code context_task_update} 切换并发的批次
 * 不做归属，因为归属关系存在歧义。已应用或失败的改写都会记录为决策。</p>
 *
 * @param trace      当前 ReAct 轨迹
 * @param batchStart 当前批次首条消息的下标
 * @param calls      当前批次的交换，用于切换检测与 read 源码版本捕获
 */
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

    /**
 * 询问成本策略这些待执行改写是否值得应用，并记录该决策。
 *
 * <p>先计算当前占用（含上一次遥测快照中 system 类别的份额）加上每个候选项节省的 token，
 * 再针对路由到的模型询问 {@link ContextCacheConfiguration#policy}。
 * 未配置缓存时无条件批准改写。</p>
 *
 * @param trace    当前 ReAct 轨迹
 * @param messages 用于估算占用的工作记忆消息
 * @param plans    候选改写
 * @param active   记录进决策的活跃任务 id
 * @return 策略允许改写时返回 {@code true}
 */
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

    /**
 * 一项待执行的替换：用更短的 artifact 预览替换一条已提交的工具结果正文。
 *
 * @param index      该工具消息在工作记忆中的位置
 * @param tool       待替换的原始消息
 * @param invocation 存储全文所用的制品键（已按 run/轮次限定作用域）
 * @param reason     随制品记录的投影原因
 * @param metadata   随制品记录携带的额外元数据
 * @param preview    将要替换原文的更短正文
 */
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

    /**
 * 把完整结果持久化为 artifact，并返回缩短后的投影占位文本。
 *
 * <p>invocation 键按 run、Reason 轮次、wire id 与 reason 限定作用域，
 * 以避免复用 wire call id 的提供方产生冲突。read 结果还会捕获工作区源码版本；
 * 任务簿记失败会被忽略，治理绝不会因此被禁用；失败时不记录任何负载内容。</p>
 *
 * @param trace        当前 ReAct 轨迹
 * @param invocationId 工具调用的 wire id
 * @param tool         工具名
 * @param content      待归档的完整结果文本
 * @param reason       随制品记录的投影原因
 * @param previewChars 内联保留的预览大小
 * @param args         工具参数，用于 read 源码版本捕获
 * @return 投影占位文本；若没有缩短或持久化失败则返回 {@code null}
 */
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

    /**
 * 持久化 {@code read} 结果，并渲染所选计划对应的投影占位文本
 *（重复引用、同版本区间增量或受限摘录）。
 *
 * @param trace        当前 ReAct 轨迹
 * @param invocationId 工具调用的 wire id
 * @param content      read 产出的完整文本
 * @param plan         已选定的投影计划，决定保留哪些可见内容
 * @param args         工具参数，用于源码版本捕获
 * @return 当占位文本符合 {@link ContextManagementOptions#readVisibleChars}
 *         且确实短于原文时返回渲染结果；否则返回 {@code null}
 */
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

    /**
 * 持久化一条结果状态（bash 诊断信息或超长错误），并在给定预算内渲染其投影占位文本；
 * 渲染成功会记录为一条 action。
 *
 * @param trace        当前 ReAct 轨迹
 * @param invocationId 工具调用的 wire id
 * @param tool         工具名
 * @param content      待归档的完整结果状态文本
 * @param plan         已选定的结果投影计划（状态码、诊断信息、头/尾）
 * @param budget       占位文本允许的最大可见字符数
 * @param args         工具参数，用于 read 源码版本捕获
 * @return 当结果符合 {@code budget} 且确实短于原文时返回渲染的占位文本；否则返回 {@code null}
 */
    private String projectOutcome(ReActTrace trace, String invocationId, String tool, String content,
                                  ToolOutcomeProjection plan, int budget, Map<String, Object> args) {
        try {
            String scopedInvocation = trace.getRunId() + ":" + trace.getCurrentTurnId() + ":"
                    + invocationId + ":" + plan.reason;
            String sessionId = trace.getSession().getSessionId();
            Map<String, String> metadata = new HashMap<>();
            if (tasks != null) {
                try { metadata.put("taskId", tasks.currentTaskId(sessionId)); }
                catch (IOException ignored) { /* Preserve a usable result when bookkeeping fails. */ }
            }
            if ("read".equals(tool)) ContextSourceVersion.capture(workspace, args, metadata);
            ContextArtifactStore.Artifact artifact = store.save(sessionId, scopedInvocation,
                    tool, content, plan.reason, 0, metadata);
            String projected = plan.render(artifact.id);
            if (projected.length() > budget || projected.length() >= content.length()) return null;
            store.recordAction(sessionId, "tool_result_projection", artifact.id,
                    plan.reason + "; visible_chars=" + projected.length(), content.length());
            return projected;
        } catch (IOException e) {
            LOG.warn("Tool diagnostic persistence failed; retaining original ({})", e.getClass().getSimpleName());
            return null;
        }
    }

    /**
 * 对在 {@code onToolCallEnd} 执行之后才进入工作记忆的错误结果重新投影——
 * 关键执行异常物化为 ToolMessage 的时机晚于单结果钩子，只能在此补救。
 *
 * @param trace          当前 ReAct 轨迹
 * @param messages       工作记忆消息
 * @param batchStart     当前批次首条消息的下标
 * @param batchIds       最后一条 Reason 消息声明的 wire id 集合
 * @param exchangersById 按 wire id 索引的交换，用于读取原始参数
 * @return 至少替换了一条消息时返回 {@code true}（工作记忆已同步更新）
 */
    private boolean projectCommittedErrors(ReActTrace trace, List<ChatMessage> messages, int batchStart,
                                           Set<String> batchIds, Map<String, ToolExchanger> exchangersById) {
        List<ChatMessage> projected = null;
        for (int i = batchStart; i < messages.size(); i++) {
            if (!(messages.get(i) instanceof ToolMessage)) continue;
            ToolMessage tool = (ToolMessage) messages.get(i);
            if (!batchIds.contains(tool.getToolCallId()) || !tool.isError() || tool.isReturnDirect()
                    || tool.hasMetadata(AgentTrace.META_FIRST) || tool.isMultiModal()
                    || !tool.getBlocks().stream().allMatch(block -> block instanceof TextBlock)
                    || !isRawText(tool.getContent())) continue;
            ToolOutcomeProjection plan = ToolOutcomeProjection.plan(tool.getName(), tool.getContent(),
                    true, options.errorVisibleChars);
            if (plan == null) continue;
            ToolExchanger source = exchangersById.get(tool.getToolCallId());
            String replacement = projectOutcome(trace, tool.getToolCallId(), tool.getName(),
                    tool.getContent(), plan, options.errorVisibleChars,
                    source == null ? null : source.getArgs());
            if (replacement == null) continue;
            if (projected == null) projected = new ArrayList<>(messages);
            ToolMessage next = ChatMessage.ofTool(ToolResult.error(replacement),
                    tool.getName(), tool.getToolCallId(), false);
            if (tool.getMetadata() != null) tool.getMetadata().forEach((key, value) -> {
                if (!"token_size".equals(key)) next.addMetadata(key, value);
            });
            projected.set(i, next);
            if (source != null && source.getToolResult() != null && source.getToolResult().isError()) {
                source.setResult(replacement);
            }
        }
        if (projected == null) return false;
        trace.getWorkingMemory().replaceMessages(projected);
        return true;
    }

    /** 结果仅由纯文本组成、且不是已有占位文本时返回 true。 */
    private static boolean textOnly(ToolResult result) {
        return result != null && result.getBlocks().stream().allMatch(block -> block instanceof TextBlock)
                && isRawText(result.getContent());
    }

    /** 文本非空、且既不是上下文 artifact 也不是投影占位文本时返回 true。 */
    private static boolean isRawText(String content) {
        return content != null && !content.isEmpty()
                && !content.startsWith("<context-artifact id=\"")
                && !content.startsWith("<tool-result-projection ");
    }

}
