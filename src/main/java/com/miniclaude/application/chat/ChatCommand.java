package com.miniclaude.application.chat;

/**
 * 发送聊天消息的用例命令对象（应用层输入 DTO）。
 * <p>
 * <b>职责</b>：将 REST {@link com.miniclaude.interfaces.rest.dto.ChatRequest} 与 HTTP 细节解耦，
 * 以不可变字段传递给 {@link ChatApplicationService#chat}。
 * <p>
 * <b>上游</b>：{@link com.miniclaude.interfaces.rest.ChatController}。
 * <b>下游</b>：{@link ChatApplicationService}。
 */
public final class ChatCommand {

    /** 目标会话 ID；{@code null} 或空白时触发隐式建会话。 */
    private final String sessionId;
    /** 用户消息正文，应用层会再次校验非空。 */
    private final String message;
    /** 可选模型覆盖。 */
    private final String model;
    /** 可选最大 Agent 轮次覆盖。 */
    private final Integer maxTurns;

    /**
     * 全字段构造；所有参数按原样保存，不做 trim（trim 在应用服务内对 message 执行）。
     *
     * @param sessionId 可 null
     * @param message   用户输入
     * @param model     可 null
     * @param maxTurns  可 null
     */
    public ChatCommand(String sessionId, String message, String model, Integer maxTurns) {
        this.sessionId = sessionId;
        this.message = message;
        this.model = model;
        this.maxTurns = maxTurns;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getMessage() {
        return message;
    }

    public String getModel() {
        return model;
    }

    public Integer getMaxTurns() {
        return maxTurns;
    }
}
