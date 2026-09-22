package com.udata.harness.common.request;

/** 工作区文本文件写入请求，path 只能是相对路径。 */
public class FileWriteRequest {
    private String path;
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
