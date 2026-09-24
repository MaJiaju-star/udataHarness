package com.udata.harness.common.domain;

/**
 * 本地工作区元数据。
 *
 * <p>工作区只保存目录引用，不拥有目录内容；移除工作区记录不会删除本地文件。</p>
 */
public class WorkspaceMetadata {
    /**
     * 注册表生成的唯一工作区标识。
     */
    private String workspaceId;

    /**
     * 展示名称，通常为目录名。
     */
    private String name;

    /**
     * 目录绝对路径。
     */
    private String path;

    /**
     * 最近打开时间戳（毫秒）。
     */
    private long lastOpenedAt;

    /**
     * 是否为当前激活工作区。
     */
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
