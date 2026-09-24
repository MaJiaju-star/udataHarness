package com.udata.harness.common.request;

/**
 * 工作区文本文件写入请求，path 只能是相对路径。
 */
public class FileWriteRequest {
    /**
     * 相对于工作区的文件路径。
     */
    private String path;

    /**
     * 完整文本内容，null 视为空文件。
     */
    private String content;

    public String getPath() {
        return path;
    }

    public void setPath(String path) {
        this.path = path;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
