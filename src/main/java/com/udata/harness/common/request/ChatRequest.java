package com.udata.harness.common.request;

/**
 * 对话请求：已有会话、用户提示词、可选模型和思考深度。
 */
public class ChatRequest {
    /**
     * 目标会话标识。
     */
    private String sessionId;

    /**
     * 用户提示词。
     */
    private String prompt;

    /**
     * 可选模型名；为空时使用会话/引擎默认模型。
     */
    private String model;

    /**
     * 可选思考深度档位：auto/none/low/medium/high/max。
     */
    private String thinkingDepth;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getPrompt() {
        return prompt;
    }

    public void setPrompt(String prompt) {
        this.prompt = prompt;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getThinkingDepth() {
        return thinkingDepth;
    }

    public void setThinkingDepth(String thinkingDepth) {
        this.thinkingDepth = thinkingDepth;
    }
}
