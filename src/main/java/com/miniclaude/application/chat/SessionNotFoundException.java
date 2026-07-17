package com.miniclaude.application.chat;

/**
 * 会话不存在异常。
 * <p>
 * 当请求的 sessionId 在仓储中查不到时抛出，由 REST 层映射为 404。
 */
public class SessionNotFoundException extends RuntimeException {

    /** 未找到的会话 ID，便于客户端定位问题。 */
    private final String sessionId;

    public SessionNotFoundException(String sessionId) {
        super("Session not found: " + sessionId);
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }
}
