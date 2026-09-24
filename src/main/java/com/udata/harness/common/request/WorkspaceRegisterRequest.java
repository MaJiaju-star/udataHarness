package com.udata.harness.common.request;

/**
 * 注册本地目录为工作区的请求。
 */
public class WorkspaceRegisterRequest {
    /**
     * 本地目录绝对路径。
     */
    private String path;

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }
}
