package com.miniclaude.application.chat;

/**
 * 发送聊天消息的用例命令对象。
 * <p>
 * 将接口层入参封装为应用服务可处理的不可变输入。
 */
public final class ChatCommand {

    /** 目标会话 ID；为空时自动创建新会话。 */
    private final String sessionId;
    /** 用户消息内容。 */
    private final String message;
    /** 可选模型覆盖，作用于本轮或新建会话。 */
    private final String model;
    /** 可选最大 Agent 轮次覆盖。 */
    private final Integer maxTurns;

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
