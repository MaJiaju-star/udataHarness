package com.udata.harness.common.request;

/** 新建或覆盖 SKILL/Subagent Markdown 内容的请求。 */
public class CapabilityRequest {
    private String name;
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
