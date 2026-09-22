package com.udata.harness.service;

import com.udata.harness.common.domain.SessionMetadata;
import com.udata.harness.common.request.CreateSessionRequest;
import com.udata.harness.common.request.SessionPermissionRequest;
import com.udata.harness.common.request.SessionTitleRequest;

import java.util.List;
import java.util.Map;

/**
 * 会话应用服务，协调仓储、运行状态、用户工作区与 HarnessEngine。
 *
 * <p>该服务是 SessionController 与底层 SessionRepository 之间的边界：负责用户归属
 * 校验、默认模型选择、瞬时运行状态合并，以及删除/取消时的资源协调。</p>
 */
public interface SessionService {
    /** 获取用户工作区、默认模型和可选模型列表等前端初始化信息。 */
    Map<String, Object> meta(String userId);

    /** 列出用户会话，并合并进程内 active 状态。 */
    List<SessionMetadata> list(String userId);

    /** 创建会话；未指定模型时使用用户 Harness 的默认模型。 */
    SessionMetadata create(String userId, CreateSessionRequest request);

    /** 更新会话级 standard/full 权限；运行过程中禁止切换。 */
    SessionMetadata updatePermission(String userId, SessionPermissionRequest request);

    /** 显式重命名会话；运行上下文与消息历史不受影响。 */
    SessionMetadata updateTitle(String userId, SessionTitleRequest request);

    /** 先取消可能存在的活动运行，再删除会话及持久化历史。 */
    void delete(String userId, String sessionId);

    /** 验证会话归属后，请求取消其活动运行。 */
    boolean cancel(String userId, String sessionId);

    /** 将 Harness ChatMessage 历史转换为前端稳定的 role/content/thinking 结构。 */
    List<Map<String, Object>> messages(String userId, String sessionId);
}
