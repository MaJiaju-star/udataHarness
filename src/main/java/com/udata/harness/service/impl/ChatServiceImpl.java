package com.udata.harness.service.impl;

import com.udata.harness.common.domain.SessionMetadata;
import com.udata.harness.common.request.ChatRequest;
import com.udata.harness.common.request.HitlDecisionRequest;
import com.udata.harness.common.support.ActiveRunRegistry;
import com.udata.harness.common.util.StreamEventMapper;
import com.udata.harness.repository.SessionRepository;
import com.udata.harness.service.ChatService;
import com.udata.harness.service.UserHarnessEngineService;
import com.udata.harness.service.UserWorkspaceService;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.agent.react.ReActTrace;
import org.noear.solon.ai.agent.react.intercept.HITL;
import org.noear.solon.ai.agent.react.intercept.HITLDecision;
import org.noear.solon.ai.agent.react.intercept.HITLTask;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.ai.chat.ChatRole;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.prompt.Prompt;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;
import reactor.core.publisher.Flux;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Solon AI Harness 流式运行适配器。
 *
 * <p>负责会话归属校验、并发控制、模型选择、工作目录注入、Chunk 协议映射以及
 * HITL 暂停/恢复。审批直接提交到原 AgentSession，确保继续同一上下文。</p>
 */
@Component
public class ChatServiceImpl implements ChatService {
    private static final int MAX_AUTO_APPROVALS = 100;
    static final String ALWAYS_ALLOWED_TOOLS_KEY = "_udata_always_allowed_tools";
    static final String SELECTED_MODEL_KEY = "_udata_selected_model";
    static final String THINKING_DEPTH_KEY = "_udata_thinking_depth";

    @Inject
    private UserHarnessEngineService engines;

    @Inject
    private SessionRepository sessionRepository;

    @Inject
    private ActiveRunRegistry activeRuns;

    @Inject
    private UserWorkspaceService workspaces;

    public Flux<String> chat(String userId, ChatRequest request) {
        validate(request);
        userId = UserWorkspaceService.requireUserId(userId);
        String sessionId = request.getSessionId();
        if (!activeRuns.begin(sessionId)) {
            return Flux.just(StreamEventMapper.error("This session is already running"));
        }

        AgentSession session;
        SessionMetadata metadata;
        try {
            session = sessionRepository.getSession(userId, sessionId);
            metadata = sessionRepository.read(userId, sessionId);
            requireActiveWorkspace(userId, metadata);
            sessionRepository.applyFirstPromptTitle(
                    userId, sessionId, request.getPrompt().trim());
        } catch (RuntimeException e) {
            activeRuns.end(sessionId);
            return Flux.just(StreamEventMapper.error(e.getMessage()));
        }

        String selectedModel = isBlank(request.getModel()) ? metadata.getModel() : request.getModel().trim();
        String thinkingDepth = normalizeThinkingDepth(request.getThinkingDepth());
        rememberRunOptions(session, selectedModel, thinkingDepth);
        return run(
                userId,
                session,
                request.getPrompt().trim(),
                selectedModel,
                thinkingDepth,
                isFullPermission(metadata),
                null);
    }

    public Flux<String> decide(String userId, HitlDecisionRequest request) {
        try {
            userId = UserWorkspaceService.requireUserId(userId);
            if (request == null || isBlank(request.getSessionId()) || isBlank(request.getAction())) {
                return Flux.just(StreamEventMapper.error("sessionId and action are required"));
            }
            AgentSession session = sessionRepository.getSession(userId, request.getSessionId());
            SessionMetadata metadata = sessionRepository.read(userId, request.getSessionId());
            requireActiveWorkspace(userId, metadata);
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

            boolean approved = "approve".equalsIgnoreCase(request.getAction().trim());
            if (approved && request.isAlwaysAllow()) {
                rememberAlwaysAllowedTools(session, selectedTasks);
            }
            for (HITLTask task : selectedTasks) {
                HITL.submit(session, task, createDecision(request));
            }
            HarnessEngine engine = engines.get(userId);
            ReActTrace trace = engine.getMainAgent().getTrace(session);
            String suspendedReasonId = trace == null ? null : trace.getCurrentReasonId();
            // 只有整批任务都有决策后才能清除 pending 并恢复。否则 ReAct 会重新 Reason，
            // 产生新的 tool call UUID，看起来像前端不断重复要求审批。
            session.pending(false, null);
            return run(
                    userId,
                    session,
                    null,
                    readSessionOption(session, SELECTED_MODEL_KEY, metadata.getModel()),
                    readSessionOption(session, THINKING_DEPTH_KEY, "auto"),
                    isFullPermission(metadata),
                    suspendedReasonId);
        } catch (RuntimeException e) {
            // 审批接口是 SSE；将同步校验异常转换为协议内错误，避免浏览器只得到空白 500。
            return Flux.just(StreamEventMapper.error(e.getMessage()));
        }
    }

    /**
     * 建立一次可取消、可恢复的 Harness 运行。
     *
     * <p>该方法负责会话归属、活动运行互斥、权限模式选择、pending HITL 状态和结束清理；
     * 真正的 Agent 分段执行委托给 {@link #runSegments}。</p>
     */
    private Flux<String> run(
            String userId,
            AgentSession session,
            String prompt,
            String selectedModel,
            String thinkingDepth,
            boolean fullPermission,
            String suspendedReasonId) {
        String sessionId = session.getSessionId();
        if (!activeRuns.isActive(sessionId) && !activeRuns.begin(sessionId)) {
            return Flux.just(StreamEventMapper.error("This session is already running"));
        }

        // session 事件固定在首位。完整权限的自动审批续跑也属于同一个外层流，
        // 因而只绑定一次取消订阅，并在所有工具链完成后统一清理 active 状态。
        return Flux.concat(
                        Flux.just(StreamEventMapper.session(sessionId)),
                        runSegments(
                                userId,
                                session,
                                prompt,
                                selectedModel,
                                thinkingDepth,
                                fullPermission,
                                0,
                                suspendedReasonId))
                .doOnSubscribe(subscription -> activeRuns.bind(sessionId, subscription))
                .doOnComplete(() -> sessionRepository.touch(userId, sessionId))
                .doFinally(signal -> activeRuns.end(sessionId));
    }

    /**
     * 执行一个 Harness 片段，并在完整权限模式下自动批准当前会话的 HITL 暂停点。
     *
     * <p>不直接切换 {@link HarnessEngine#setHitlEnabled(Boolean)}，因为 Engine 按用户
     * 共享，全局开关会让同一用户的其他标准权限会话也绕过审批。</p>
     */
    /**
     * 执行当前分段并把 AgentChunk 映射成稳定 SSE JSON。
     *
     * <p>遇到 HITL 时只记录挂起任务并结束当前段；审批接口随后携带 decision 恢复同一
     * 会话。完整权限模式会自动批准，但通过计数上限防止模型无限工具循环。</p>
     */
    private Flux<String> runSegments(
            String userId,
            AgentSession session,
            String prompt,
            String selectedModel,
            String thinkingDepth,
            boolean fullPermission,
            int autoApprovalCount,
            String suspendedReasonId) {
        Path workspace = workspaces.getOrCreate(userId);
        HarnessEngine engine = engines.get(userId);
        Flux<String> chunks = engine.prompt(prompt)
                .session(session)
                .options(options -> {
                    options.toolContextPut(HarnessEngine.ATTR_CWD, workspace.toString());
                    if (!isBlank(selectedModel)) {
                        options.chatModel(engine.getModelOrDefInstance(selectedModel));
                    }
                    if ("none".equals(thinkingDepth)) {
                        options.thinking(false);
                    } else if (!"auto".equals(thinkingDepth)) {
                        options.reasoning_effort(thinkingDepth);
                    }
                })
                .stream()
                // HITL 审批恢复会重放挂起前同一 reasonId 的文本；该文本已经在上一个
                // SSE 分段发给浏览器，必须在映射前过滤，避免前端再次追加。
                .filter(chunk -> !StreamEventMapper.isReasonReplay(chunk, suspendedReasonId))
                .map(StreamEventMapper::map);

        return chunks.concatWith(Flux.defer(() -> {
            List<HITLTask> pendingTasks = HITL.getPendingTasks(session);
            if (pendingTasks.isEmpty()) {
                return Flux.empty();
            }

            // Solon ReAct 在挂起结束时会把 pendingReason 当作一次临时 Assistant 终态，
            // 同时写入 Session 与当前 Trace 的 WorkingMemory。它只用于标记“等待审批”，
            // 若保留下来，恢复后的模型会把它当成真实历史，连续多个工具便会反复复述
            // 之前的正文和风险提示。结构化 hitl 事件已完整承载该状态，因此在返回审批
            // 事件或自动续跑前清除这条临时消息。
            removePendingMarker(engine, session);

            boolean sessionAlwaysAllows = areAllToolsAlwaysAllowed(session, pendingTasks);
            if (!fullPermission && !sessionAlwaysAllows) {
                return Flux.just(StreamEventMapper.hitl(pendingTasks));
            }
            if (autoApprovalCount + pendingTasks.size() > MAX_AUTO_APPROVALS) {
                return Flux.just(StreamEventMapper.error(
                        "Too many automatic tool approvals in one run"));
            }

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
            String nextSuspendedReasonId = trace == null ? null : trace.getCurrentReasonId();
            session.pending(false, null);
            return runSegments(
                    userId,
                    session,
                    null,
                    selectedModel,
                    thinkingDepth,
                    fullPermission,
                    autoApprovalCount + pendingTasks.size(),
                    nextSuspendedReasonId);
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
     * 根据前端回传的 callUuid 定位本轮任务。旧版前端未传 callUuids 时默认选择整批，
     * 从而保持接口兼容，同时避免退回到存在歧义的 toolName 定位方式。
     */
    /** 按 callUuid 精确选择待审批任务，兼容同批次多个同名 Tool。 */
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

    private HITLDecision createDecision(HitlDecisionRequest request) {
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
                return HITLDecision.skip(request.getComment());
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

    /** 判断当前批次是否全部属于本会话已授权的工具。 */
    boolean areAllToolsAlwaysAllowed(AgentSession session, List<HITLTask> tasks) {
        if (tasks == null || tasks.isEmpty()) {
            return false;
        }
        Set<String> allowed = readAlwaysAllowedTools(session);
        return tasks.stream()
                .allMatch(task -> task != null && allowed.contains(task.getToolName()));
    }

    private Set<String> readAlwaysAllowedTools(AgentSession session) {
        Object stored = session.getContext().get(ALWAYS_ALLOWED_TOOLS_KEY);
        Set<String> tools = new LinkedHashSet<>();
        if (stored instanceof Collection<?> values) {
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

    /** 取消指定会话的活动 Reactor 订阅，并返回是否存在可取消运行。 */
    public boolean cancel(String sessionId) {
        return activeRuns.cancel(sessionId);
    }

    private void validate(ChatRequest request) {
        if (request == null || isBlank(request.getSessionId())) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (isBlank(request.getPrompt())) {
            throw new IllegalArgumentException("prompt is required");
        }
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    /** 保存本轮模型和思考深度，使 HITL 恢复时继续使用相同推理配置。 */
    private void rememberRunOptions(AgentSession session, String selectedModel, String thinkingDepth) {
        session.getContext().put(SELECTED_MODEL_KEY, selectedModel);
        session.getContext().put(THINKING_DEPTH_KEY, thinkingDepth);
        session.updateSnapshot();
    }

    /** 读取会话运行选项，旧会话没有该字段时使用回退值。 */
    private String readSessionOption(AgentSession session, String key, String fallback) {
        Object value = session.getContext().get(key);
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    /** 将前端思考深度归一化为 Solon AI 支持的统一档位。 */
    String normalizeThinkingDepth(String value) {
        String normalized = value == null ? "auto" : value.trim().toLowerCase();
        return switch (normalized) {
            case "none", "low", "medium", "high", "max" -> normalized;
            default -> "auto";
        };
    }

    private boolean isFullPermission(SessionMetadata metadata) {
        return metadata != null && "full".equals(metadata.getPermissionMode());
    }

    /** 拒绝在其他工作区继续会话，防止工具在错误目录中运行。 */
    private void requireActiveWorkspace(String userId, SessionMetadata metadata) {
        String activeWorkspaceId = workspaces.getActive(userId).getWorkspaceId();
        if (metadata == null || !activeWorkspaceId.equals(metadata.getWorkspaceId())) {
            throw new IllegalArgumentException("Session belongs to another workspace");
        }
    }
}
