package com.miniclaude.interfaces.rest.dto;

import javax.validation.constraints.NotBlank;

/**
 * 聊天请求体。
 */
public class ChatRequest {

    /** 会话 ID；省略时服务端自动创建新会话。 */
    private String sessionId;

    /** 用户消息，必填。 */
    @NotBlank
    private String message;

    /** 可选模型覆盖，作用于本轮或新建会话。 */
    private String model;

    /** 可选最大 Agent 轮次覆盖。 */
    private Integer maxTurns;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Integer getMaxTurns() {
        return maxTurns;
    }

    public void setMaxTurns(Integer maxTurns) {
        this.maxTurns = maxTurns;
    }
}
