package com.udata.harness.common.request;

/** 创建会话请求；空模型由 SessionService 使用 Harness 默认模型补齐。 */
public class CreateSessionRequest {
    private String title;
    private String model;

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }
}
