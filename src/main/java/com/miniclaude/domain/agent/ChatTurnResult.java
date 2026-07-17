package com.miniclaude.domain.agent;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * 单次 Agent 对话轮次的执行结果。
 */
public final class ChatTurnResult {

    /** 所属会话 ID。 */
    private final String sessionId;
    /** 助手回复文本，空值归一化为空字符串。 */
    private final String reply;
    /** Token 用量统计，常见键为 input / output。 */
    private final Map<String, Integer> tokens;
    /** 实际使用的模型标识。 */
    private final String model;

    public ChatTurnResult(String sessionId, String reply, Map<String, Integer> tokens, String model) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.reply = reply != null ? reply : "";
        this.tokens = tokens != null ? tokens : Collections.emptyMap();
        this.model = model;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getReply() {
        return reply;
    }

    public Map<String, Integer> getTokens() {
        return tokens;
    }

    public String getModel() {
        return model;
    }
}
