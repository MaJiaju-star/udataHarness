package com.udata.harness.common.request;

/**
 * 新建或覆盖 SKILL/Subagent Markdown 内容的请求。
 */
public class CapabilityRequest {
    /**
     * 能力名称，需符合名称白名单。
     */
    private String name;

    /**
     * Markdown 定义内容。
     */
    private String content;

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }
}
