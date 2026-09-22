package com.udata.harness.service.impl;

import com.udata.harness.common.domain.SessionMetadata;
import com.udata.harness.common.domain.WorkspaceMetadata;
import com.udata.harness.common.request.CreateSessionRequest;
import com.udata.harness.common.request.SessionPermissionRequest;
import com.udata.harness.common.request.SessionTitleRequest;
import com.udata.harness.common.support.ActiveRunRegistry;
import com.udata.harness.repository.SessionRepository;
import com.udata.harness.service.ChatService;
import com.udata.harness.service.SessionService;
import com.udata.harness.service.UserHarnessEngineService;
import com.udata.harness.service.UserWorkspaceService;
import org.noear.solon.ai.chat.ChatConfig;
import org.noear.solon.ai.agent.AgentSession;
import org.noear.solon.ai.chat.message.ChatMessage;
import org.noear.solon.ai.chat.message.AssistantMessage;
import org.noear.solon.ai.chat.message.ToolMessage;
import org.noear.solon.ai.chat.tool.ToolCall;
import org.noear.solon.ai.harness.HarnessEngine;
import org.noear.solon.annotation.Component;
import org.noear.solon.annotation.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话应用服务实现。
 *
 * <p>负责协调会话仓储、用户工作区、HarnessEngine 和活动运行注册表，使
 * Controller 不直接依赖持久化或 Solon AI 运行时对象。</p>
 */
@Component
public class SessionServiceImpl implements SessionService {
    @Inject
    private SessionRepository sessionRepository;

    @Inject
    private ChatService chatService;

    @Inject
    private ActiveRunRegistry activeRuns;

    @Inject
    private UserHarnessEngineService engines;

    @Inject
    private UserWorkspaceService workspaces;

    /**
     * 返回当前用户可见的运行时元数据。
     *
     * <p>模型列表来自用户引擎而非配置文件快照，确保热更新后 UI 展示的是实际可选模型。</p>
     */
    @Override
    public Map<String, Object> meta(String userId) {
        String safeUserId = UserWorkspaceService.requireUserId(userId);
        HarnessEngine engine = engines.get(safeUserId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("name", "UData Harness");
        data.put("userId", safeUserId);
        data.put("workspace", workspaces.getOrCreate(safeUserId).toString());
        data.put("activeWorkspace", workspaces.getActive(safeUserId));
        data.put("workspaces", workspaces.list(safeUserId));
        data.put("defaultModel", engine.getDefaultModel());
        List<Map<String, Object>> models = new ArrayList<>();
        for (ChatConfig config : engine.getModels()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", config.getNameOrModel());
            item.put("model", config.getModel());
            item.put("provider", config.getProvider());
            item.put("contextLength", config.getContextLength());
            models.add(item);
        }
        data.put("models", models);
        return data;
    }

    /** 列出用户会话，并用进程内运行注册表补充瞬时 active 状态。 */
    @Override
    public List<SessionMetadata> list(String userId) {
        String workspaceId = workspaces.getActive(userId).getWorkspaceId();
        List<SessionMetadata> sessions = sessionRepository.list(userId).stream()
                .filter(item -> workspaceId.equals(item.getWorkspaceId()))
                .toList();
        // active 是进程内实时状态，不写回会话元数据文件。
        sessions.forEach(item -> item.setActive(activeRuns.isActive(item.getSessionId())));
        return sessions;
    }

    /**
     * 创建持久化会话元数据并确保用户工作区存在。
     *
     * <p>请求未指定模型时使用该用户引擎的默认模型，最终选择会固定在会话元数据中。</p>
     */
    @Override
    public SessionMetadata create(String userId, CreateSessionRequest request) {
        HarnessEngine engine = engines.get(userId);
        String title = request == null ? null : request.getTitle();
        String model = request == null ? null : request.getModel();
        if (model == null || model.isBlank()) {
            model = engine.getDefaultModel();
        }
        WorkspaceMetadata workspace = workspaces.getActive(userId);
        return sessionRepository.create(userId, title, model, workspace.getWorkspaceId());
    }

    /**
     * 修改会话权限模式。
     *
     * <p>运行中的会话禁止切换权限，避免同一轮工具调用前后使用不同授权策略。</p>
     */
    @Override
    public SessionMetadata updatePermission(
            String userId, SessionPermissionRequest request) {
        if (request == null || request.getSessionId() == null
                || request.getSessionId().isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        if (activeRuns.isActive(request.getSessionId())) {
            throw new IllegalStateException(
                    "Cannot change permission mode while the session is running");
        }
        return sessionRepository.updatePermissionMode(
                UserWorkspaceService.requireUserId(userId),
                request.getSessionId(),
                request.getPermissionMode());
    }

    /** 显式重命名会话，只更新产品元数据。 */
    @Override
    public SessionMetadata updateTitle(String userId, SessionTitleRequest request) {
        if (request == null || request.getSessionId() == null
                || request.getSessionId().isBlank()) {
            throw new IllegalArgumentException("sessionId is required");
        }
        return sessionRepository.updateTitle(
                UserWorkspaceService.requireUserId(userId),
                request.getSessionId(),
                request.getTitle());
    }

    /**
     * 删除会话前先取消活动订阅，防止流结束回调在目录删除后再次写入快照。
     */
    @Override
    public void delete(String userId, String sessionId) {
        // 先取消订阅，防止流结束回调在目录删除后再次 touch 会话。
        chatService.cancel(sessionId);
        sessionRepository.delete(userId, sessionId);
    }

    /** 校验会话归属后取消当前运行；返回值表示是否确实找到活动订阅。 */
    @Override
    public boolean cancel(String userId, String sessionId) {
        sessionRepository.read(userId, sessionId);
        return chatService.cancel(sessionId);
    }

    /**
     * 将框架消息转换成稳定的前端历史协议。
     *
     * <p>AssistantMessage 中的 ToolCall 先创建工具卡片；后续 ToolMessage 按 callId
     * 回填输出，不再单独生成“助手消息”。因此实时 SSE 与刷新后的历史消息结构一致，
     * {@code render_echart} 的 option 也能从工具结果中恢复。</p>
     */
    @Override
    public List<Map<String, Object>> messages(String userId, String sessionId) {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Map<String, Object>> pendingTools = new LinkedHashMap<>();
        AgentSession session = sessionRepository.getSession(userId, sessionId);
        Object storedActivities = session.getContext().get(ChatServiceImpl.FILE_ACTIVITIES_KEY);
        Map<?, ?> activitiesByRun = storedActivities instanceof Map<?, ?> map ? map : Map.of();
        for (ChatMessage message : session.getMessages()) {
            if (message instanceof ToolMessage toolMessage) {
                Map<String, Object> tool = pendingTools.get(toolMessage.getToolCallId());
                if (tool != null) {
                    tool.put("output", toolMessage.getContent());
                    tool.put("running", false);
                }
                // Tool output is represented inside its assistant message instead
                // of appearing as a second assistant chat bubble.
                continue;
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("role", message.getRole().name().toLowerCase());
            item.put("content", message.getContent());
            item.put("thinking", message.isThinking());
            if (message instanceof AssistantMessage assistantMessage
                    && assistantMessage.getToolCalls() != null
                    && !assistantMessage.getToolCalls().isEmpty()) {
                List<Map<String, Object>> tools = new ArrayList<>();
                for (ToolCall call : assistantMessage.getToolCalls()) {
                    Map<String, Object> tool = new LinkedHashMap<>();
                    tool.put("callId", call.getId());
                    tool.put("name", call.getName());
                    tool.put("args", call.getArguments());
                    tool.put("running", false);
                    tools.add(tool);
                    if (call.getId() != null) {
                        pendingTools.put(call.getId(), tool);
                    }
                }
                item.put("tools", tools);
            }
            if (message instanceof AssistantMessage) {
                Object runId = message.getMetadata().get("_runId");
                Object fileActivities = runId == null ? null : activitiesByRun.get(String.valueOf(runId));
                if (fileActivities != null) {
                    item.put("fileActivities", fileActivities);
                }
            }
            result.add(item);
        }
        return result;
    }
}
