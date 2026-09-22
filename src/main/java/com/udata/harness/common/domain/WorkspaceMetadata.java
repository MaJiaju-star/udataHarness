package com.udata.harness.common.domain;

/**
 * 本地工作区元数据。
 *
 * <p>工作区只保存目录引用，不拥有目录内容；移除工作区记录不会删除本地文件。</p>
 */
public class WorkspaceMetadata {
    private String workspaceId;
    private String name;
    private String path;
    private long lastOpenedAt;
    private boolean active;

    public String getWorkspaceId() { return workspaceId; }
    public void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
    public long getLastOpenedAt() { return lastOpenedAt; }
    public void setLastOpenedAt(long lastOpenedAt) { this.lastOpenedAt = lastOpenedAt; }
    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }
}
