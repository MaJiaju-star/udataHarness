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
import org.noear.solon.Utils;
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
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 会话应用服务实现。
 *
 * <p>负责协调会话仓储、用户工作区、HarnessEngine 和活动运行注册表，使
 * Controller 不直接依赖持久化或 Solon AI 运行时对象。</p>
 */
@Component
public class SessionServiceImpl implements SessionService {
    /**
     * 会话仓储，负责会话元数据与消息的读写。
     */
    @Inject
    private SessionRepository sessionRepository;

    /**
     * 聊天服务，用于删除会话时终止其正在进行的运行。
     */
    @Inject
    private ChatService chatService;

    /**
     * 活动运行注册表，用于校验会话是否仍有运行及执行取消。
     */
    @Inject
    private ActiveRunRegistry activeRuns;

    /**
     * 用户引擎服务，用于读取当前用户可用的模型列表与加载状态。
     */
    @Inject
    private UserHarnessEngineService engines;

    /**
     * 用户工作区服务，用于校验会话所属工作区与当前激活工作区是否一致。
     */
    @Inject
    private UserWorkspaceService workspaces;

    /**
     * 返回当前用户可见的运行时元数据。
     *
     * <p>模型列表来自用户引擎而非配置文件快照，确保热更新后 UI 展示的是实际可选模型。</p>
     *
     * @param userId 当前用户标识
     * @return 包含工作区、默认模型、可选模型列表与沙箱状态的元数据
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
        data.put("sandboxEnabled", engines.isSandboxEnabled(safeUserId));
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

    /**
     * 列出用户会话，并用进程内运行注册表补充瞬时 active 状态。
     *
     * @param userId 当前用户标识
     * @return 当前激活工作区下的会话列表
     */
    @Override
    public List<SessionMetadata> list(String userId) {
        String workspaceId = workspaces.getActive(userId).getWorkspaceId();
        List<SessionMetadata> sessions = sessionRepository.list(userId).stream()
                .filter(item -> workspaceId.equals(item.getWorkspaceId()))
                .collect(Collectors.toList());
        // active 是进程内实时状态，不写回会话元数据文件。
        sessions.forEach(item -> item.setActive(activeRuns.isActive(item.getSessionId())));
        return sessions;
    }

    /**
     * 创建持久化会话元数据并确保用户工作区存在。
     *
     * <p>请求未指定模型时使用该用户引擎的默认模型，最终选择会固定在会话元数据中。</p>
     *
     * @param userId 当前用户标识
     * @param request 可选标题与模型；为空时全部使用默认值
     * @return 已持久化的会话元数据
     */
    @Override
    public SessionMetadata create(String userId, CreateSessionRequest request) {
        HarnessEngine engine = engines.get(userId);
        String title = request == null ? null : request.getTitle();
        String model = request == null ? null : request.getModel();
        if (Utils.isBlank(model)) {
            model = engine.getDefaultModel();
        }
        WorkspaceMetadata workspace = workspaces.getActive(userId);
        return sessionRepository.create(userId, title, model, workspace.getWorkspaceId());
    }

    /**
     * 修改会话权限模式。
     *
     * <p>运行中的会话禁止切换权限，避免同一轮工具调用前后使用不同授权策略。</p>
     *
     * @param userId 当前用户标识
     * @param request 目标 sessionId 与目标权限模式
     * @return 更新后的会话元数据
     * @throws IllegalArgumentException sessionId 为空时抛出
     * @throws IllegalStateException 会话正在运行时抛出
     */
    @Override
    public SessionMetadata updatePermission(
            String userId, SessionPermissionRequest request) {
        if (request == null || Utils.isBlank(request.getSessionId())) {
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

    /**
     * 更新用户级沙箱开关。
     *
     * <p>同一用户的会话共享 HarnessEngine，因此只要存在运行中的会话就禁止切换，
     * 避免一轮工具执行期间安全边界发生变化。</p>
     *
     * @param userId 当前用户标识
     * @param enabled 是否启用沙箱
     * @return 后端实际应用的沙箱状态
     * @throws IllegalStateException 存在运行中会话时抛出
     */
    @Override
    public boolean updateSandbox(String userId, boolean enabled) {
        String safeUserId = UserWorkspaceService.requireUserId(userId);
        boolean running = sessionRepository.list(safeUserId).stream()
                .anyMatch(item -> activeRuns.isActive(item.getSessionId()));
        if (running) {
            throw new IllegalStateException("Cannot change sandbox mode while an agent is running");
        }
        engines.setSandboxEnabled(safeUserId, enabled);
        return enabled;
    }

    /**
     * 显式重命名会话，只更新产品元数据。
     *
     * @param userId 当前用户标识
     * @param request 目标 sessionId 与新标题
     * @return 更新后的会话元数据
     */
    @Override
    public SessionMetadata updateTitle(String userId, SessionTitleRequest request) {
        if (request == null || Utils.isBlank(request.getSessionId())) {
            throw new IllegalArgumentException("sessionId is required");
        }
        return sessionRepository.updateTitle(
                UserWorkspaceService.requireUserId(userId),
                request.getSessionId(),
                request.getTitle());
    }

    /**
     * 删除会话前先取消活动订阅，防止流结束回调在目录删除后再次写入快照。
     *
     * @param userId 当前用户标识
     * @param sessionId 待删除会话标识
     */
    @Override
    public void delete(String userId, String sessionId) {
        // 先取消订阅，防止流结束回调在目录删除后再次 touch 会话。
        chatService.cancel(sessionId);
        sessionRepository.delete(userId, sessionId);
    }

    /**
     * 校验会话归属后取消当前运行。
     *
     * @param userId 当前用户标识
     * @param sessionId 目标会话标识
     * @return 是否确实找到活动订阅
     */
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
     *
     * @param userId 当前用户标识
     * @param sessionId 目标会话标识
     * @return 可展示的消息历史；工具输出已合并进对应 Assistant 消息
     */
    @Override
    public List<Map<String, Object>> messages(String userId, String sessionId) {
        List<Map<String, Object>> result = new ArrayList<>();
        Map<String, Map<String, Object>> pendingTools = new LinkedHashMap<>();
        AgentSession session = sessionRepository.getSession(userId, sessionId);
        //1. 取出按 runId 归档的文件活动记录，用于在 Assistant 消息上回填文件变更。
        Object storedActivities = session.getContext().get(ChatServiceImpl.FILE_ACTIVITIES_KEY);
        Map<?, ?> activitiesByRun = storedActivities instanceof Map<?, ?>
                ? (Map<?, ?>) storedActivities : Collections.emptyMap();
        //2. 顺序遍历历史：ToolMessage 按 callId 回填工具输出，其余消息转为 UI 结构。
        for (ChatMessage message : session.getMessages()) {
            if (message instanceof ToolMessage) {
                ToolMessage toolMessage = (ToolMessage) message;
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
            if (message instanceof AssistantMessage
                    && ((AssistantMessage) message).getToolCalls() != null
                    && !((AssistantMessage) message).getToolCalls().isEmpty()) {
                AssistantMessage assistantMessage = (AssistantMessage) message;
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
