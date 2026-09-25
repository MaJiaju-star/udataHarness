package com.udata.harness.service;

import com.udata.harness.common.domain.SessionMetadata;

import java.util.List;
import java.util.Map;

/**
 * 会话应用服务，协调仓储、运行状态、用户工作区与 HarnessEngine。
 *
 * <p>该服务是 SessionController 与底层 SessionRepository 之间的边界：负责用户归属
 * 校验、默认模型选择、瞬时运行状态合并，以及删除/取消时的资源协调。</p>
 */
public interface SessionService {
    /**
     * 获取用户工作区、默认模型和可选模型列表等前端初始化信息。
     *
     * @param userId 当前用户标识
     * @return 前端启动所需的元数据
     */
    Map<String, Object> meta(String userId);

    /**
     * 列出用户会话，并合并进程内 active 状态。
     *
     * @param userId 当前用户标识
     * @return 会话元数据列表
     */
    List<SessionMetadata> list(String userId);

    /**
     * 创建会话；未指定模型时使用用户 Harness 的默认模型。
     *
     * @param userId 当前用户标识
     * @param title 可选标题
     * @param model 可选模型
     * @return 已持久化的会话元数据
     */
    SessionMetadata create(String userId, String title, String model);

    /**
     * 更新会话级 standard/full 权限；运行过程中禁止切换。
     *
     * @param userId 当前用户标识
     * @param sessionId 目标会话标识
     * @param permissionMode 目标权限模式
     * @return 更新后的会话元数据
     */
    SessionMetadata updatePermission(String userId, String sessionId, String permissionMode);

    /**
     * 更新用户工作区级沙箱开关；该用户存在运行中会话时禁止切换。
     *
     * @param userId 当前用户标识
     * @param enabled 是否启用沙箱
     * @return 后端实际应用的沙箱状态
     */
    boolean updateSandbox(String userId, boolean enabled);

    /**
     * 显式重命名会话；运行上下文与消息历史不受影响。
     *
     * @param userId 当前用户标识
     * @param sessionId 目标会话标识
     * @param title 新标题
     * @return 更新后的会话元数据
     */
    SessionMetadata updateTitle(String userId, String sessionId, String title);

    /**
     * 先取消可能存在的活动运行，再删除会话及持久化历史。
     *
     * @param userId 当前用户标识
     * @param sessionId 待删除会话标识
     */
    void delete(String userId, String sessionId);

    /**
     * 验证会话归属后，请求取消其活动运行。
     *
     * @param userId 当前用户标识
     * @param sessionId 目标会话标识
     * @return 是否找到并标记了活动运行
     */
    boolean cancel(String userId, String sessionId);

    /**
     * 将 Harness ChatMessage 历史转换为前端稳定的 role/content/thinking 结构。
     *
     * @param userId 当前用户标识
     * @param sessionId 目标会话标识
     * @return 可展示的消息历史
     */
    List<Map<String, Object>> messages(String userId, String sessionId);
}
