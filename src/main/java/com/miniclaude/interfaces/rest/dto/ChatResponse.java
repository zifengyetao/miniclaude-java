package com.miniclaude.interfaces.rest.dto;

import java.util.Map;

/**
 * 聊天响应体。
 */
public class ChatResponse {

    /** 会话 ID，新建会话时客户端需保存以便续聊。 */
    private String sessionId;
    /** 助手回复文本。 */
    private String reply;
    /** 实际使用的模型。 */
    private String model;
    /** Token 用量，键通常为 input / output。 */
    private Map<String, Integer> tokens;

    public ChatResponse() {
    }

    public ChatResponse(String sessionId, String reply, String model, Map<String, Integer> tokens) {
        this.sessionId = sessionId;
        this.reply = reply;
        this.model = model;
        this.tokens = tokens;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getReply() {
        return reply;
    }

    public void setReply(String reply) {
        this.reply = reply;
    }

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public Map<String, Integer> getTokens() {
        return tokens;
    }

    public void setTokens(Map<String, Integer> tokens) {
        this.tokens = tokens;
    }
}
