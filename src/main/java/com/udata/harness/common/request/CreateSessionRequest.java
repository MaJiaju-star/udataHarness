package com.udata.harness.common.request;

/**
 * 创建会话请求；空模型由 SessionService 使用 Harness 默认模型补齐。
 */
public class CreateSessionRequest {
    /**
     * 可选会话标题，为空时使用占位标题。
     */
    private String title;

    /**
     * 可选模型名。
     */
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
