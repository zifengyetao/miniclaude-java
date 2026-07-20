package com.miniclaude.application.chat;

/**
 * 会话不存在异常（应用层 → 接口层边界异常）。
 * <p>
 * <b>职责</b>：当请求的 {@code sessionId} 在 {@link com.miniclaude.domain.session.SessionRepository}
 * 中不存在时抛出，携带 ID 供 REST 层构造 404 响应体。
 * <p>
 * <b>下游</b>：{@link com.miniclaude.interfaces.rest.RestExceptionHandler#notFound} 映射 HTTP 404。
 */
public class SessionNotFoundException extends RuntimeException {

    /** 客户端请求但未找到的会话 ID。 */
    private final String sessionId;

    /**
     * @param sessionId 未找到的会话标识，写入 {@link #getMessage()} 与 {@link #getSessionId()}
     */
    public SessionNotFoundException(String sessionId) {
        super("Session not found: " + sessionId);
        this.sessionId = sessionId;
    }

    /** @return 未找到的会话 ID，不为 null */
    public String getSessionId() {
        return sessionId;
    }
}
