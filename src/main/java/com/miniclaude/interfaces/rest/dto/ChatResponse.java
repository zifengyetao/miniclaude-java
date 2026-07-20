package com.miniclaude.interfaces.rest.dto;

import java.util.Map;

/**
 * 聊天 POST 响应体 DTO。
 * <p>
 * 封装单轮对话结果；客户端应持久化 {@code sessionId} 以支持多轮续聊。
 */
public class ChatResponse {

    /** 会话唯一标识；新建会话时客户端必须保存以便后续请求携带。 */
    private String sessionId;
    /** 助手本轮回复的纯文本内容。 */
    private String reply;
    /** 实际参与推理的模型名称（可能与请求覆盖或会话绑定模型一致）。 */
    private String model;
    /** Token 用量统计；键通常为 {@code input}、{@code output}，值为整数计数。 */
    private Map<String, Integer> tokens;

    /** Jackson/JSON 反序列化用的无参构造。 */
    public ChatResponse() {
    }

    /**
     * 全字段构造，供控制器从 {@link com.miniclaude.domain.agent.ChatTurnResult} 映射。
     */
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
