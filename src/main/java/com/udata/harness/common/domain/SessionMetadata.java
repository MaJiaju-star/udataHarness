package com.udata.harness.common.domain;

/**
 * 会话持久化元数据。
 *
 * <p>{@code active} 是服务层按运行注册表动态填充的瞬时字段；{@code permissionMode}
 * 使用稳定的产品值 standard/full。</p>
 */
public class SessionMetadata {
    /**
     * 全局唯一的会话标识。
     */
    private String sessionId;

    /**
     * 会话所属用户标识。
     */
    private String userId;

    /**
     * 会话绑定的工作区标识。
     */
    private String workspaceId;

    /**
     * 会话标题。
     */
    private String title;

    /**
     * 会话使用的模型名。
     */
    private String model;

    /**
     * 权限模式：standard 或 full。
     */
    private String permissionMode;

    /**
     * 创建时间戳（毫秒）。
     */
    private long createdAt;

    /**
     * 最近更新时间戳（毫秒）。
     */
    private long updatedAt;

    /**
     * 是否存在活动运行（瞬时字段，不持久化）。
     */
    private boolean active;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getWorkspaceId() {
        return workspaceId;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getPermissionMode() {
        return permissionMode;
    }

    public void setPermissionMode(String permissionMode) {
        this.permissionMode = permissionMode;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(long createdAt) {
        this.createdAt = createdAt;
    }

    public long getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(long updatedAt) {
        this.updatedAt = updatedAt;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }
}
