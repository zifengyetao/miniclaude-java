package com.miniclaude.interfaces.rest.dto;

import javax.validation.constraints.NotBlank;

/**
 * 聊天 POST 请求体 DTO。
 * <p>
 * 由 {@code ChatController} 接收并经 Bean Validation 校验后转为 {@link com.miniclaude.application.chat.ChatCommand}。
 */
public class ChatRequest {

    /** 目标会话 ID；省略或为空时服务端自动创建新会话并在响应中返回新 ID。 */
    private String sessionId;

    /** 用户消息正文，必填，不可为空白。 */
    @NotBlank
    private String message;

    /** 可选模型 ID 覆盖；作用于本轮推理及（若新建）会话默认模型。 */
    private String model;

    /** 可选 Agent 最大推理轮次覆盖；不传时使用全局 {@code AgentSettings} 默认值。 */
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
