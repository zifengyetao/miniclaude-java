package com.miniclaude.domain.agent;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;

/**
 * 单次 Agent 对话轮次的执行结果（不可变值对象）。
 * <p>
 * <b>为何放在 domain：</b>表达 Chat 用例的输出契约，与具体引擎返回结构解耦。
 * <p>
 * <b>不变量：</b>
 * <ul>
 *   <li>{@code sessionId} 非空，与请求 context 中的 sessionId 一致（由实现保证）。</li>
 *   <li>{@code reply} 永不为 {@code null}，空回复归一化为 {@code ""}。</li>
 *   <li>{@code tokens} 永不为 {@code null}，缺失时为空不可变映射；常见键为 input/output。</li>
 * </ul>
 * <p>
 * <b>边界：</b>由 {@link AgentGateway} 实现产出；application 层映射为 REST DTO，不做二次业务推断。
 */
public final class ChatTurnResult {

    /** 所属会话 ID，与 {@link ExecutionContext#getSessionId()} 对应。 */
    private final String sessionId;
    /** 助手回复文本；{@code null} 入参归一化为空字符串。 */
    private final String reply;
    /** Token 用量统计（如 input/output）；防御性复制为不可变映射。 */
    private final Map<String, Integer> tokens;
    /** 实际使用的模型标识，可能与 settings 中请求模型不同（路由/降级时）。 */
    private final String model;

    /**
     * 构造一轮对话结果。
     *
     * @param sessionId 会话 ID，不可为 null
     * @param reply     助手文本，可为 null（归一化为 ""）
     * @param tokens    用量映射，可为 null（归一化为空映射）
     * @param model     实际模型名，可为 null
     */
    public ChatTurnResult(String sessionId, String reply, Map<String, Integer> tokens, String model) {
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        this.reply = reply != null ? reply : "";
        this.tokens = tokens != null ? tokens : Collections.emptyMap();
        this.model = model;
    }

    /** @return 会话 ID */
    public String getSessionId() {
        return sessionId;
    }

    /** @return 助手回复，永不为 null */
    public String getReply() {
        return reply;
    }

    /** @return 不可变 token 用量映射 */
    public Map<String, Integer> getTokens() {
        return tokens;
    }

    /** @return 实际使用的模型标识 */
    public String getModel() {
        return model;
    }
}
