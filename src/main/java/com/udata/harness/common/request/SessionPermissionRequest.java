package com.udata.harness.common.request;

/**
 * 修改单个会话的权限模式。
 *
 * <p>{@code standard} 在敏感工具执行前请求用户审批；{@code full} 自动批准当前
 * 会话中的工具调用。</p>
 */
public class SessionPermissionRequest {
    private String sessionId;
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
