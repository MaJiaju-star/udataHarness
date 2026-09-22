package com.udata.harness.common.request;

/** 显式重命名会话的请求。 */
public class SessionTitleRequest {
    private String sessionId;
    private String title;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }
}
