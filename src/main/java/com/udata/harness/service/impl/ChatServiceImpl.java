package com.udata.harness.service.impl;

import com.udata.harness.common.domain.SessionMetadata;
import com.udata.harness.common.request.ChatRequest;
import com.udata.harness.common.request.HitlDecisionRequest;
import com.udata.harness.common.support.ActiveRunRegistry;
import com.udata.harness.common.support.ToolCallStreamInterceptor;
import com.udata.harness.common.util.StreamEventMapper;
import com.udata.harness.repository.SessionRepository;
import com.udata.harness.repository.UsageAuditRepository;
import com.udata.harness.service.ChatService;
import com.udata.harness.service.UserHarnessEngineService;
import com.udata.harness.service.UserWorkspaceService;
import org.noear.solon.Utils;
import org.noear.snack4.ONode;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.AgentEvent;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLDecision;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.agent.react.task.ToolCallEndEvent;
import org.noear.solon.ai.agent.react.task.ToolCallStartEvent;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Solon AI Harness 流式运行适配器。
 *
 * <p>负责会话归属校验、并发控制、模型选择、工作目录注入、Chunk 协议映射以及
 * HITL 暂停/恢复。审批直接提交到原 AgentSession，确保继续同一上下文。</p>
 */
@Component
public class ChatServiceImpl implements ChatService {
    /**
     * 单次运行内自动批准工具的调用次数上限，防止授权失控。
     */
    private static final int MAX_AUTO_APPROVALS = 100;
    static final String REJECTED_TURN_MESSAGE = "你已拒绝执行，本轮任务已终止。";
    static final String SKIPPED_TOOL_MESSAGE = "用户选择跳过此工具，本次调用未执行。请向用户说明原计划执行的操作、用途，"
            + "以及跳过后对任务的影响。不得声称已执行或编造结果；未经用户重新明确授权，"
            + "不要重试此操作或通过其他工具执行等价操作。";

    /**
     * 会话中记录“始终允许”工具集合的元数据键。
     */
    static final String ALWAYS_ALLOWED_TOOLS_KEY = "_udata_always_allowed_tools";

    /**
     * 会话中记录用户所选模型的元数据键。
     */
    static final String SELECTED_MODEL_KEY = "_udata_selected_model";

    /**
     * 会话中记录思考深度的元数据键。
     */
    static final String THINKING_DEPTH_KEY = "_udata_thinking_depth";

    /**
     * 会话中记录已有文件操作记录的元数据键。
     */
    static final String FILE_ACTIVITIES_KEY = "_udata_file_activities";

    /**
     * 会话中记录待审批文件操作的元数据键，用于 HITL 断点恢复。
     */
    private static final String PENDING_FILE_ACTIVITIES_KEY = "_udata_pending_file_activities";

    /**
     * 用户引擎服务，用于获取/构建当前用户的 HarnessEngine。
     */
    @Inject
    private UserHarnessEngineService engines;

    /**
     * 会话仓储，用于加载会话及其历史消息、持久化运行选项。
     */
    @Inject
    private SessionRepository sessionRepository;

    @Inject
    private UsageAuditRepository usageAuditRepository;

    /**
     * 活动运行注册表，用于占位、校验与取消正在进行的运行。
     */
    @Inject
    private ActiveRunRegistry activeRuns;

    @Inject private com.udata.harness.common.support.ScheduledRunEvents scheduledRunEvents;
    @Inject private com.udata.harness.notification.NotificationService notifications;
    @Inject private com.udata.harness.memory.MemoryService memories;

    /**
     * 用户工作区服务，用于校验会话工作区并解析工作目录。
     */
    @Inject
    private UserWorkspaceService workspaces;

    /**
     * 模型请求总尝试次数，包含第一次请求。
     */
    @Inject("${agent.model.retry.max-attempts:3}")
    private int modelMaxAttempts;

    /**
     * 模型重试指数退避的基础等待时间。
     */
    @Inject("${agent.model.retry.initial-delay-ms:1000}")
    private long modelRetryInitialDelayMs;

    /**
     * 在已有会话中提交用户提示词并启动一次 Harness 流式运行。
     *
     * <p>进入运行前完成请求校验、会话归属校验、活动运行互斥占位、工作区一致性检查
     * 以及首问标题回填。运行选项（模型、思考深度）写入会话快照，供后续 HITL 恢复复用。</p>
     *
     * @param userId 当前用户标识，经安全校验后用于会话归属判定
     * @param request 会话标识与用户提示词，可选模型与思考深度
     * @return 逐事件发射的 SSE JSON 字符串流；校验失败时返回单条 error 事件
     */
    public Flux<String> chat(String userId, ChatRequest request) {
        return chatInternal(userId, request, null);
    }

    @Override
    public Flux<String> chatScheduled(String userId, ChatRequest request, String executionId) {
        if (isBlank(executionId)) throw new IllegalArgumentException("executionId is required");
        return chatInternal(userId, request, executionId);
    }

    private Flux<String> chatInternal(String userId, ChatRequest request, String executionId) {
        //1. 校验请求参数，并把 userId 归一化为可安全用于目录名与归属比较的形式。
        validate(request);
        userId = UserWorkspaceService.requireUserId(userId);
        String promptWithContext = request.promptWithContext();
        String sessionId = request.getSessionId();
        try {
            SessionMetadata before = sessionRepository.read(userId, sessionId);
            requireActiveWorkspace(userId, before);
            // Apply saved model changes before this new run occupies the active registry.
            engines.get(userId, before.getWorkspaceId(), before.getCapabilityMode());
        } catch (RuntimeException e) { return Flux.just(StreamEventMapper.error(e.getMessage())); }
        if (!activeRuns.begin(sessionId, executionId)) {
            return Flux.just(StreamEventMapper.error("This session is already running"));
        }

        //2. 加载会话与元数据，校验其属于当前激活工作区，并按首问回填标题。
        AgentSession session;
        SessionMetadata metadata;
        try {
            session = sessionRepository.getSession(userId, sessionId);
            metadata = sessionRepository.read(userId, sessionId);
            requireActiveWorkspace(userId, metadata);
            if (!HITL.getPendingTasks(session).isEmpty()) {
                throw new IllegalStateException("Resolve the pending approval before sending another prompt");
            }
            sessionRepository.applyFirstPromptTitle(
                    userId, sessionId, request.getPrompt().trim());
        } catch (RuntimeException e) {
            // 准备阶段的失败必须释放互斥占位，否则该会话将永远无法再次发起。
            activeRuns.end(sessionId);
            return Flux.just(StreamEventMapper.error(e.getMessage()));
        }

        //3. 解析本轮模型与思考深度并持久化到会话快照，再交出执行。
        String selectedModel = isBlank(request.getModel()) ? metadata.getModel() : request.getModel().trim();
        String thinkingDepth = normalizeThinkingDepth(request.getThinkingDepth());
        try {
            rememberRunOptions(session, selectedModel, thinkingDepth);
            if (memories != null) {
                // Use only the user's own message for write intent; editor attachments are untrusted source data.
                session.getContext().put(com.udata.harness.memory.MemoryService.SNAPSHOT_KEY,
                        memories.snapshot(userId, metadata.getWorkspaceId(), request.getPrompt()));
                session.updateSnapshot();
            }
            session.getContext().put(com.udata.harness.common.support.ScheduledRunEvents.SESSION_KEY,
                    executionId == null ? "" : executionId);
            if (executionId != null) session.updateSnapshot();
            if (notifications != null) {
                try {
                    SessionMetadata notificationMetadata = sessionRepository.read(userId, sessionId);
                    notifications.begin(session, notificationMetadata, workspaces.resolve(userId, notificationMetadata.getWorkspaceId()).getName(), executionId);
                } catch (RuntimeException notificationError) { org.slf4j.LoggerFactory.getLogger(getClass()).warn("Cannot initialize conversation notification for {}", sessionId); }
            }
            return run(
                userId,
                session,
                promptWithContext,
                selectedModel,
                thinkingDepth,
                isFullPermission(metadata),
                null, false);
        } catch (RuntimeException e) {
            activeRuns.end(sessionId);
            session.getContext().put(com.udata.harness.common.support.ScheduledRunEvents.SESSION_KEY, "");
            if (notifications != null) notifications.ended(userId, session, "ON_ERROR", false, false);
            return Flux.just(StreamEventMapper.error(e.getMessage()));
        }
    }

    /**
     * 提交用户审批决策，并从 Harness 的 HITL 暂停点继续运行。
     *
     * <p>恢复前必须为当前批次的所有挂起任务提交决策：Solon 4.0.4 起决策键使用
     * callUuid，同一批次可能包含多个同名 Tool，因此不能按 toolName 定位。</p>
     *
     * @param userId 当前用户标识
     * @param request 审批动作、备注、可选修改参数及永久允许标记
     * @return 审批恢复后的连续 SSE JSON 事件流；校验失败时返回单条 error 事件
     */
    public Flux<String> decide(String userId, HitlDecisionRequest request) {
        String lockedSessionId = null;
        boolean handedOff = false;
        try {
            //1. 校验请求与会话归属，并确认会话仍属于当前激活工作区。
            userId = UserWorkspaceService.requireUserId(userId);
            if (request == null || isBlank(request.getSessionId()) || isBlank(request.getAction())) {
                return Flux.just(StreamEventMapper.error("sessionId and action are required"));
            }
            AgentSession session = sessionRepository.getSession(userId, request.getSessionId());
            SessionMetadata metadata = sessionRepository.read(userId, request.getSessionId());
            requireActiveWorkspace(userId, metadata);
            if (!activeRuns.begin(request.getSessionId(), session.getContext().getAs(
                    com.udata.harness.common.support.ScheduledRunEvents.SESSION_KEY))) {
                return Flux.just(StreamEventMapper.error("This session is already running"));
            }
            lockedSessionId = request.getSessionId();

            //2. 取出全部挂起任务，并按前端回传的 callUuid 选出本次要决策的整批任务。
            // 批量工具调用会一次产生多个 HITLTask，且可能具有相同 toolName。
            // Solon 4.0.4 起决策键必须使用 callUuid，恢复前必须为整批任务提交决策。
            List<HITLTask> pendingTasks = HITL.getPendingTasks(session);
            if (pendingTasks.isEmpty()) {
                return Flux.just(StreamEventMapper.error("No pending HITL task"));
            }
            List<HITLTask> selectedTasks = selectPendingTasks(pendingTasks, request.getCallUuids());
            if (selectedTasks.size() != pendingTasks.size()) {
                return Flux.just(StreamEventMapper.error(
                        "All pending HITL tasks must be decided together"));
            }

            HarnessEngine engine = engines.get(userId, metadata.getWorkspaceId(), metadata.getCapabilityMode());
            ReActTrace trace = engine.getMainAgent().getTrace(session);
            if ("reject".equalsIgnoreCase(request.getAction().trim())) {
                removePendingMarker(engine, session);
                rejectPendingTurn(session, trace);
                Map<String, Object> text = new LinkedHashMap<>();
                text.put("type", "text");
                text.put("reasonId", "hitl-reject-" + java.util.UUID.randomUUID());
                text.put("content", "\n\n" + REJECTED_TURN_MESSAGE);
                Map<String, Object> end = new LinkedHashMap<>();
                end.put("type", "run_end");
                if (trace != null) end.put("runId", trace.getRunId());
                end.put("status", "cancelled");
                end.put("terminationReason", "hitl_rejected");
                Flux<String> termination = bindRun(userId, session, Flux.just(
                        StreamEventMapper.session(session.getSessionId()), hitlDecisionEvent("reject"),
                        ONode.serialize(text), ONode.serialize(end)), true);
                handedOff = true;
                return termination;
            }

            //3. 记录会话级“始终允许”工具（若用户勾选），再为每个任务提交决策。
            boolean approved = "approve".equalsIgnoreCase(request.getAction().trim());
            if (approved && request.isAlwaysAllow()) {
                rememberAlwaysAllowedTools(session, selectedTasks);
            }
            for (HITLTask task : selectedTasks) {
                HITL.submit(session, task, createDecision(request));
            }

            //4. 记录挂起前的 reasonId 用于过滤恢复时的重放文本，再清除 pending 并恢复运行。
            String suspendedReasonId = trace == null ? null : trace.getCurrentTurnId();
            // 只有整批任务都有决策后才能清除 pending 并恢复。否则 ReAct 会重新 Reason，
            // 产生新的 tool call UUID，看起来像前端不断重复要求审批。
            session.pending(false, null);
            boolean explainOnly = "skip".equalsIgnoreCase(request.getAction().trim());
            Flux<String> continuation = run(
                    userId,
                    session,
                    null,
                    readSessionOption(session, SELECTED_MODEL_KEY, metadata.getModel()),
                    readSessionOption(session, THINKING_DEPTH_KEY, "auto"),
                    isFullPermission(metadata),
                    suspendedReasonId, explainOnly);
            if (explainOnly) continuation = Flux.concat(Flux.just(hitlDecisionEvent("skip")), continuation);
            handedOff = true;
            return continuation;
        } catch (RuntimeException e) {
            // 审批接口是 SSE；将同步校验异常转换为协议内错误，避免浏览器只得到空白 500。
            return Flux.just(StreamEventMapper.error(e.getMessage()));
        } finally {
            if (lockedSessionId != null && !handedOff) activeRuns.end(lockedSessionId);
        }
    }

    /**
     * 建立一次可取消、可恢复的 Harness 运行。
     *
     * <p>该方法负责活动运行互斥、session 首事件发射、取消订阅绑定和结束清理；
     * 真正的 Agent 分段执行委托给 {@link #runSegments}。</p>
     *
     * @param userId 当前用户标识
     * @param session 已校验归属的 AgentSession
     * @param prompt 本轮用户提示词；HITL 恢复时为 {@code null}
     * @param selectedModel 本轮使用的模型名，可为空表示用引擎默认模型
     * @param thinkingDepth 归一化后的思考深度档位
     * @param fullPermission 是否为自动批准权限模式
     * @param suspendedReasonId 挂起前的 reasonId，用于过滤恢复重放；首次运行传 null
     * @return 绑定取消订阅的 SSE JSON 事件流
     */
    private Flux<String> run(
            String userId,
            AgentSession session,
            String prompt,
            String selectedModel,
            String thinkingDepth,
            boolean fullPermission,
            String suspendedReasonId, boolean explainOnly) {
        //1. 确保持有活动运行占位；已有占位说明本会话正在运行，直接拒绝。
        String sessionId = session.getSessionId();
        if (!activeRuns.isActive(sessionId) && !activeRuns.begin(sessionId)) {
            return Flux.just(StreamEventMapper.error("This session is already running"));
        }

        if (prompt == null && notifications != null) notifications.resume(session);

        //2. 组装事件流：session 事件固定首位，随后执行分段；订阅时绑定取消句柄，
        //   完成时刷新时间戳，任何终止信号都统一释放活动状态。
        // session 事件固定在首位。完整权限的自动审批续跑也属于同一个外层流，
        // 因而只绑定一次取消订阅，并在所有工具链完成后统一清理 active 状态。
        return bindRun(userId, session, Flux.concat(
                        Flux.just(StreamEventMapper.session(sessionId)),
                        runSegments(
                                userId,
                                session,
                                prompt,
                                selectedModel,
                                thinkingDepth,
                                fullPermission,
                                0,
                                suspendedReasonId, explainOnly)), false);
    }

    private String hitlDecisionEvent(String action) {
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("type", "hitl_decision"); event.put("action", action);
        return ONode.serialize(event);
    }

    /** Same persistence, cancellation and background-job completion for execution and explicit rejection. */
    private Flux<String> bindRun(String userId, AgentSession session, Flux<String> source, boolean rejected) {
        String sessionId = session.getSessionId();
        final String executionId = session.getContext().getAs(com.udata.harness.common.support.ScheduledRunEvents.SESSION_KEY);
        if (rejected && notifications != null) notifications.resume(session);
        return source
                .doOnSubscribe(subscription -> activeRuns.bind(sessionId, subscription))
                .transform(stream -> activeRuns.cancellable(sessionId, stream))
                .doOnComplete(() -> sessionRepository.touch(userId, sessionId))
                .doOnNext(event -> {
                    if (scheduledRunEvents != null) scheduledRunEvents.event(executionId, event);
                    if (notifications != null && (rejected || event.contains("error"))) {
                        Map<String, Object> notificationEvent = ONode.ofJson(event).toBean(Map.class);
                        if (rejected || "error".equals(notificationEvent.get("type"))) notifications.observe(session, notificationEvent);
                    }
                })
                .doFinally(signal -> {
                    boolean stopped = rejected || activeRuns.wasStopped(sessionId);
                    boolean pending = session.isPending() || !HITL.getPendingTasks(session).isEmpty();
                    try {
                        if (notifications != null) notifications.ended(userId, session, signal.name(), stopped, pending);
                        if (executionId != null && !executionId.isEmpty() && !pending) {
                            session.getContext().put(com.udata.harness.common.support.ScheduledRunEvents.SESSION_KEY, ""); session.updateSnapshot();
                        }
                    } finally {
                        if (scheduledRunEvents != null) scheduledRunEvents.ended(executionId, stopped ? "CANCEL" : signal.name(), pending);
                        activeRuns.end(sessionId);
                    }
                });
    }

    /** End without invoking the engine: no tool in a rejected batch may execute or trigger another model call. */
    void rejectPendingTurn(AgentSession session, ReActTrace trace) {
        List<HITLTask> pending = new ArrayList<>(HITL.getPendingTasks(session));
        List<ChatMessage> messages = new ArrayList<>();
        AssistantMessage reason = trace == null ? null : trace.getLastReasonMessage();
        if (reason != null && reason.isToolCalls()) {
            // ActionTask persists this pair only after execution. Record unexecuted results
            // so a later user turn never inherits dangling provider tool-call IDs.
            messages.add(reason);
            for (ToolCall call : reason.getToolCalls()) {
                messages.add(ChatMessage.ofTool("用户已拒绝执行，此工具未执行，本轮任务已终止。", call.getName(), call.getId()));
            }
        }
        messages.add(ChatMessage.ofAssistant(REJECTED_TURN_MESSAGE));
        session.addMessage(messages);
        HITL.clear(session);
        for (HITLTask task : pending) session.getContext().remove("_hitl_applied_" + task.getCallUuid());
        session.getContext().remove(PENDING_FILE_ACTIVITIES_KEY);
        session.pending(false, null);
        if (trace != null) {
            trace.getWorkingMemory().addMessage(messages);
            trace.setLastReasonMessage(null);
            trace.setFinalAnswer(REJECTED_TURN_MESSAGE, false);
            trace.setRoute(org.noear.solon.ai.agent.Agent.ID_END);
        }
        session.updateSnapshot();
    }

    /**
     * 执行一个 Harness 分段，并把 AgentEvent 映射为稳定的 SSE JSON。
     *
     * <p>遇到 HITL 时只记录挂起任务并结束当前段；审批接口随后携带 decision 恢复同一
     * 会话。完整权限模式会自动批准，但通过计数上限防止模型无限工具循环。</p>
     *
     * <p>不直接切换 {@link HarnessEngine#setHitlEnabled(Boolean)}，因为 Engine 按用户
     * 共享，全局开关会让同一用户的其他标准权限会话也绕过审批。</p>
     *
     * @param userId 当前用户标识
     * @param session 当前 AgentSession
     * @param prompt 本次提示词；续跑分段为 {@code null}
     * @param selectedModel 本轮使用的模型名，可为空
     * @param thinkingDepth 归一化后的思考深度档位
     * @param fullPermission 是否为自动批准权限模式
     * @param autoApprovalCount 本次运行已自动批准的工具数，用于限制循环
     * @param suspendedReasonId 上一段挂起前的 reasonId，用于过滤重放文本
     * @return 本分段及其后续自动批准分段的 SSE JSON 事件流
     */
    private Flux<String> runSegments(
            String userId,
            AgentSession session,
            String prompt,
            String selectedModel,
            String thinkingDepth,
            boolean fullPermission,
            int autoApprovalCount,
            String suspendedReasonId, boolean explainOnly) {
        // Resolve from the persisted session, including on deferred HITL continuation threads.
        SessionMetadata metadata = sessionRepository.read(userId, session.getSessionId());
        Path workspace = java.nio.file.Paths.get(workspaces.resolve(userId, metadata.getWorkspaceId()).getPath());
        HarnessEngine engine = engines.get(userId, metadata.getWorkspaceId(), metadata.getCapabilityMode());
        //1. 组装本次模型调用的重试、拦截器、cwd、模型与思考档位配置。
        Object saved = session.getContext().get(com.udata.harness.memory.MemoryService.SNAPSHOT_KEY);
        final Map<String, Object> memorySnapshot = saved instanceof Map ? (Map<String, Object>) saved : java.util.Collections.emptyMap();
        final com.udata.harness.common.support.ExplainOnlyAfterSkipInterceptor skipGuard = explainOnly
                ? new com.udata.harness.common.support.ExplainOnlyAfterSkipInterceptor(SKIPPED_TOOL_MESSAGE) : null;
        Flux<String> chunks = engine.prompt(prompt)
                .session(session)
                .options(options -> {
                    options.retryConfig(modelMaxAttempts, modelRetryInitialDelayMs);
                    options.interceptorAdd(new ToolCallStreamInterceptor(explainOnly));
                    if (explainOnly) {
                        options.interceptorAdd(skipGuard, Integer.MAX_VALUE);
                    }
                    options.toolContextPut(HarnessEngine.ATTR_CWD, workspace.toString());
                    com.udata.harness.memory.MemoryTalent.configure(options, memories, userId,
                            metadata.getWorkspaceId(), session.getSessionId(), memorySnapshot);
                    if (!isBlank(selectedModel)) {
                        if (engine.getModelOrNil(selectedModel) == null)
                            throw new IllegalArgumentException("所选模型不存在或已停用，请重新选择模型");
                        options.chatModel(engine.getModelOrDefInstance(selectedModel));
                    }
                    if ("none".equals(thinkingDepth)) {
                        options.thinking(false);
                    } else if (!"auto".equals(thinkingDepth)) {
                        options.reasoning_effort(thinkingDepth);
                    }
                })
                .stream()
                .doFinally(signal -> { if (skipGuard != null) skipGuard.setEnabled(false); })
                // HITL 审批恢复会重放挂起前同一 reasonId 的文本；该文本已经在上一个
                // SSE 分段发给浏览器，必须在映射前过滤，避免前端再次追加。
                .filter(chunk -> !StreamEventMapper.isReasonReplay(chunk, suspendedReasonId))
                .doOnNext(chunk -> {
                    if (!explainOnly) trackFileActivity(workspace, session, chunk);
                    if (engine.getMainAgent().name().equals(chunk.getAgentName()))
                        com.udata.harness.memory.MemoryService.recordRecall(session, chunk.getRunId(), memorySnapshot);
                })
                .map(chunk -> {
                    Map<String, Object> event = StreamEventMapper.mapData(chunk);
                    if (explainOnly && (chunk instanceof ToolCallStartEvent || chunk instanceof ToolCallEndEvent))
                        event.put("disposition", "skip");
                    if (notifications != null) notifications.observe(session, event);
                    usageAuditRepository.record(userId, session.getSessionId(),
                            metadata.getWorkspaceId(), selectedModel, event);
                    return ONode.serialize(event);
                });

        //2. 过滤 HITL 恢复重放的 Reason 文本，跟踪文件活动并映射为标准 SSE JSON；
        //   随后在流结束时检查是否出现新的挂起任务。
        Map<String, Object> recallEvent = com.udata.harness.memory.MemoryService.recallView(memorySnapshot);
        return Flux.concat(Flux.just(ONode.serialize(recallEvent)), chunks).concatWith(Flux.defer(() -> {
            List<HITLTask> pendingTasks = HITL.getPendingTasks(session);
            if (pendingTasks.isEmpty()) {
                return Flux.empty();
            }

            // Solon ReAct 在挂起结束时会把 pendingReason 当作一次临时 Assistant 终态，
            // 同时写入 Session 与当前 Trace 的 WorkingMemory。它只用于标记“等待审批”，
            // 若保留下来，恢复后的模型会把它当成真实历史，连续多个工具便会反复复述
            // 之前的正文和风险提示。结构化 hitl 事件已完整承载该状态，因此在返回审批
            // 事件或自动续跑前清除这条临时消息。
            //3. 清除框架为“等待审批”写入的临时 Assistant 消息，避免恢复后被当作真实历史。
            removePendingMarker(engine, session);

            //4. 标准权限且本批工具未全部授权时，发射 hitl 事件并结束本段等待用户决策。
            boolean sessionAlwaysAllows = areAllToolsAlwaysAllowed(session, pendingTasks);
            if (!fullPermission && !sessionAlwaysAllows) {
                return Flux.just(StreamEventMapper.hitl(pendingTasks));
            }
            if (autoApprovalCount + pendingTasks.size() > MAX_AUTO_APPROVALS) {
                return Flux.just(StreamEventMapper.error(
                        "Too many automatic tool approvals in one run"));
            }

            //5. 自动批准本批任务，记录新的 reasonId 后递归执行下一分段。
            // 不使用框架的 alwaysAllow 回调。该回调写入用户级 Engine，且 bash/file
            // 默认只按当前参数生成规则，与界面承诺的“本会话始终允许此工具”不一致。
            String approvalComment = fullPermission
                    ? "approved by full permission mode"
                    : "approved by session tool permission";
            for (HITLTask task : pendingTasks) {
                HITL.submit(
                        session,
                        task,
                        HITLDecision.approve(false).comment(approvalComment));
            }
            ReActTrace trace = engine.getMainAgent().getTrace(session);
            String nextSuspendedReasonId = trace == null ? null : trace.getCurrentTurnId();
            session.pending(false, null);
            return runSegments(
                    userId,
                    session,
                    null,
                    selectedModel,
                    thinkingDepth,
                    fullPermission,
                    autoApprovalCount + pendingTasks.size(),
                    nextSuspendedReasonId, explainOnly);
        }));
    }

    /**
     * 删除框架为 HITL 挂起生成的临时 Assistant 消息。
     *
     * <p>删除采用“角色为 Assistant 且内容严格等于 pendingReason”的双重条件，只处理
     * 最新一条消息，避免误删模型真正生成的正文。Session 与 ReAct WorkingMemory 各有
     * 一份副本，必须同步清理并重新保存快照。</p>
     */
    private void removePendingMarker(HarnessEngine engine, AgentSession session) {
        String pendingReason = session.getPendingReason();
        if (isBlank(pendingReason)) {
            return;
        }

        List<ChatMessage> messages = session.getMessages();
        if (!messages.isEmpty() && isPendingMarker(messages.get(messages.size() - 1), pendingReason)) {
            session.removeLatestMessage(1);
        }

        ReActTrace trace = engine.getMainAgent().getTrace(session);
        if (trace != null) {
            Prompt workingMemory = trace.getWorkingMemory();
            ChatMessage latest = workingMemory.getLastMessage();
            if (isPendingMarker(latest, pendingReason)) {
                workingMemory.removeLastMessage();
            }
            if (pendingReason.equals(trace.getFinalAnswer())) {
                trace.setFinalAnswer(null, false);
            }
        }
        session.updateSnapshot();
    }

    private boolean isPendingMarker(ChatMessage message, String pendingReason) {
        return message != null
                && message.getRole() == ChatRole.ASSISTANT
                && pendingReason.equals(message.getContent());
    }

    /**
     * 根据前端回传的 callUuid 精确定位本轮待审批任务。旧版前端未传 callUuids 时默认选择整批，
     * 从而保持接口兼容，同时避免退回到存在歧义的 toolName 定位方式。
     *
     * @param pendingTasks 当前会话全部挂起任务
     * @param requestedCallUuids 前端指定的 callUuid 列表，可为空
     * @return 命中的任务子列表；未指定时返回全部挂起任务
     */
    private List<HITLTask> selectPendingTasks(
            List<HITLTask> pendingTasks, List<String> requestedCallUuids) {
        if (requestedCallUuids == null || requestedCallUuids.isEmpty()) {
            return pendingTasks;
        }
        Set<String> requested = new HashSet<>(requestedCallUuids);
        List<HITLTask> selected = new ArrayList<>();
        for (HITLTask task : pendingTasks) {
            if (requested.contains(task.getCallUuid())) {
                selected.add(task);
            }
        }
        return selected;
    }

    /**
     * 根据前端动作构造对应的 HITLDecision。
     *
     * @param request 审批请求，action 支持 approve/skip/reject
     * @return 可直接提交给框架的决策对象
     * @throws IllegalArgumentException 动作不受支持时抛出
     */
    HITLDecision createDecision(HitlDecisionRequest request) {
        HITLDecision decision;
        switch (request.getAction().trim().toLowerCase()) {
            case "approve":
                // 会话级授权由本服务持久化。始终向框架提交一次性批准，避免其默认回调
                // 把参数级规则写进按用户共享的 HarnessEngine。
                decision = HITLDecision.approve(false).comment(request.getComment());
                if (request.getModifiedArgs() != null) {
                    decision.modifiedArgs(request.getModifiedArgs());
                }
                return decision;
            case "skip":
                return HITLDecision.skip(SKIPPED_TOOL_MESSAGE + (isBlank(request.getComment())
                        ? "" : "\n用户补充说明：" + request.getComment()));
            case "reject":
                return HITLDecision.reject(request.getComment());
            default:
                throw new IllegalArgumentException("Unsupported HITL action");
        }
    }

    /**
     * 将用户勾选的工具名写入 AgentSession 快照。
     *
     * <p>只保存工具名，不保存本次 command/file_path，因此同一会话内该工具后续参数变化
     * 仍可自动放行；数据跟随会话快照持久化，不会影响同一用户的其他会话。</p>
     *
     * @param session 目标会话
     * @param tasks 本批已批准的任务，其 toolName 会被合并去重后持久化
     */
    void rememberAlwaysAllowedTools(AgentSession session, List<HITLTask> tasks) {
        Set<String> tools = new LinkedHashSet<>(readAlwaysAllowedTools(session));
        for (HITLTask task : tasks) {
            if (task != null && !isBlank(task.getToolName())) {
                tools.add(task.getToolName().trim());
            }
        }
        session.getContext().put(ALWAYS_ALLOWED_TOOLS_KEY, new ArrayList<>(tools));
        session.updateSnapshot();
    }

    /**
     * 判断当前批次是否全部属于本会话已授权的工具。
     *
     * @param session 目标会话
     * @param tasks 待判定任务
     * @return 非空且每个任务的 toolName 均在会话白名单中时为 true
     */
    boolean areAllToolsAlwaysAllowed(AgentSession session, List<HITLTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return false;
        }
        Set<String> allowed = readAlwaysAllowedTools(session);
        return tasks.stream()
                .allMatch(task -> task != null && allowed.contains(task.getToolName()));
    }

    /**
     * 读取会话级“始终允许”工具集合。
     *
     * @param session 目标会话
     * @return 去重后的工具名集合；未设置时为空集合
     */
    private Set<String> readAlwaysAllowedTools(AgentSession session) {
        Object stored = session.getContext().get(ALWAYS_ALLOWED_TOOLS_KEY);
        Set<String> tools = new LinkedHashSet<>();
        if (stored instanceof Collection<?>) {
            Collection<?> values = (Collection<?>) stored;
            for (Object value : values) {
                if (value != null && !isBlank(String.valueOf(value))) {
                    tools.add(String.valueOf(value).trim());
                }
            }
        } else if (stored != null && !isBlank(String.valueOf(stored))) {
            tools.add(String.valueOf(stored).trim());
        }
        return tools;
    }

    /**
     * 取消指定会话的活动 Reactor 订阅。
     *
     * @param sessionId 目标会话标识
     * @return 找到活动运行并发出取消信号时为 true
     */
    public boolean cancel(String sessionId) {
        return activeRuns.cancel(sessionId);
    }

    /**
     * 校验对话请求的必要字段。
     *
     * @param request 待校验请求
     * @throws IllegalArgumentException sessionId 或 prompt 为空时抛出
     */
    private void validate(ChatRequest request) {
        if (request == null || isBlank(request.getSessionId())) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (isBlank(request.getPrompt())) {
            throw new IllegalArgumentException("prompt is required");
        }
    }

    private boolean isBlank(String value) {
        return Utils.isBlank(value);
    }

    /**
     * 保存本轮模型和思考深度，使 HITL 恢复时继续使用相同推理配置。
     *
     * @param session 目标会话
     * @param selectedModel 本轮模型名
     * @param thinkingDepth 归一化后的思考深度档位
     */
    private void rememberRunOptions(AgentSession session, String selectedModel, String thinkingDepth) {
        session.getContext().put(SELECTED_MODEL_KEY, selectedModel);
        session.getContext().put(THINKING_DEPTH_KEY, thinkingDepth);
        session.updateSnapshot();
    }

    /**
     * 读取会话运行选项。
     *
     * @param session 目标会话
     * @param key 会话上下文键
     * @param fallback 缺失或为空时的回退值
     * @return 选项值或回退值
     */
    private String readSessionOption(AgentSession session, String key, String fallback) {
        Object value = session.getContext().get(key);
        return value == null || Utils.isBlank(String.valueOf(value)) ? fallback : String.valueOf(value);
    }

    /**
     * 将前端思考深度归一化为 Solon AI 支持的统一档位。
     *
     * @param value 原始档位值
     * @return none/low/medium/high/max 之一；无法识别时返回 auto
     */
    String normalizeThinkingDepth(String value) {
        String normalized = value == null ? "auto" : value.trim().toLowerCase();
        if ("none".equals(normalized) || "low".equals(normalized)
                || "medium".equals(normalized) || "high".equals(normalized)
                || "max".equals(normalized)) {
            return normalized;
        }
        return "auto";
    }

    /**
     * 判断会话是否使用自动批准权限模式。
     *
     * @param metadata 会话元数据，可为 null
     * @return permissionMode 为 full 时返回 true
     */
    private boolean isFullPermission(SessionMetadata metadata) {
        return metadata != null && "full".equals(metadata.getPermissionMode());
    }

    /**
     * 跟踪 read/write/edit 工具的成功闭环，并按 runId 保存到 Session 快照。
     *
     * <p>Action 阶段记录路径和写入前是否存在，Observation 成功后才正式计入，避免把
     * 被拒绝或执行失败的工具展示成已完成文件变更。</p>
     *
     * @param workspace 当前用户工作区根目录，用于判定 write 是新建还是覆盖
     * @param session 目标会话
     * @param event 单个 AgentEvent
     */
    @SuppressWarnings("unchecked")
    void trackFileActivity(Path workspace, AgentSession session, AgentEvent event) {
        //1. Action 阶段识别文件工具，并暂存 callId 对应的活动记录。
        if (event instanceof ToolCallStartEvent) {
            ToolCallStartEvent action = (ToolCallStartEvent) event;
            String name = action.getToolName() == null ? "" : action.getToolName().toLowerCase();
            if (!("read".equals(name) || "write".equals(name) || "edit".equals(name))) {
                return;
            }
            Object rawPath = action.getArgs() == null ? null : action.getArgs().get("file_path");
            if (!(rawPath instanceof String)) {
                return;
            }
            String filePath = (String) rawPath;
            if (Utils.isBlank(filePath) || filePath.startsWith("@")) {
                return;
            }
            String type = "read";
            if ("edit".equals(name)) {
                type = "modified";
            } else if ("write".equals(name)) {
                Path target = workspace.resolve(filePath).normalize();
                type = target.startsWith(workspace) && Files.exists(target) ? "modified" : "created";
            }
            action.getMeta().put("fileOperation", type);
            Object pendingValue = session.getContext().get(PENDING_FILE_ACTIVITIES_KEY);
            Map<String, Map<String, String>> pending;
            if (pendingValue instanceof Map<?, ?>) {
                pending = (Map<String, Map<String, String>>) pendingValue;
            } else {
                pending = new LinkedHashMap<>();
                session.getContext().put(PENDING_FILE_ACTIVITIES_KEY, pending);
            }
            Map<String, String> record = new LinkedHashMap<>();
            record.put("type", type);
            record.put("path", filePath);
            pending.put(action.getCallId(), record);
            return;
        }

        //2. Observation 阶段仅持久化成功操作，并按类型和路径去重。
        if (event instanceof ToolCallEndEvent) {
            ToolCallEndEvent observation = (ToolCallEndEvent) event;
            Map<String, Map<String, String>> pending = (Map<String, Map<String, String>>)
                    session.getContext().get(PENDING_FILE_ACTIVITIES_KEY);
            if (pending == null) return;
            Map<String, String> record = pending.remove(observation.getCallId());
            String output = observation.getText();
            if (record == null || observation.getError() != null
                    || (output != null && output.matches("(?s).*(错误|失败|not found|cannot ).*"))) {
                return;
            }
            Object activitiesValue = session.getContext().get(FILE_ACTIVITIES_KEY);
            Map<String, List<Map<String, String>>> activities;
            if (activitiesValue instanceof Map<?, ?>) {
                activities = (Map<String, List<Map<String, String>>>) activitiesValue;
            } else {
                activities = new LinkedHashMap<>();
                session.getContext().put(FILE_ACTIVITIES_KEY, activities);
            }
            List<Map<String, String>> runActivities = activities.computeIfAbsent(
                    observation.getRunId(), key -> new ArrayList<>());
            boolean duplicate = runActivities.stream().anyMatch(item ->
                    record.get("type").equals(item.get("type"))
                            && record.get("path").equals(item.get("path")));
            if (!duplicate) {
                runActivities.add(record);
                session.updateSnapshot();
            }
        }
    }

    /**
     * 拒绝在其他工作区继续会话，防止工具在错误目录中运行。
     *
     * @param userId 当前用户标识
     * @param metadata 会话元数据
     * @throws IllegalArgumentException 会话不属于当前激活工作区时抛出
     */
    private void requireActiveWorkspace(String userId, SessionMetadata metadata) {
        if (metadata == null) throw new IllegalArgumentException("Session not found");
        workspaces.resolve(userId, metadata.getWorkspaceId());
    }
}
