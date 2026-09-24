package com.udata.harness.common.request;

/**
 * 修改单个会话的权限模式。
 *
 * <p>{@code standard} 在敏感工具执行前请求用户审批；{@code full} 自动批准当前
 * 会话中的工具调用。</p>
 */
public class SessionPermissionRequest {
    /**
     * 目标会话标识。
     */
    private String sessionId;

    /**
     * 目标权限模式：standard 或 full。
     */
    private String permissionMode;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getPermissionMode() {
        return permissionMode;
    }

    public void setPermissionMode(String permissionMode) {
        this.permissionMode = permissionMode;
    }
}
