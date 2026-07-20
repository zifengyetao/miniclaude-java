package com.miniclaude.domain.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行时执行结果的不可变传输对象。
 * <p>
 * <b>为何放在 domain：</b>隔离上层与具体引擎返回结构（OpenAI/Anthropic/内部 Agent）。
 * <p>
 * <b>不变量：</b>text 永非 null（空串）；tokens 为不可变映射副本。
 * <p>
 * <b>边界：</b>{@link AgentRuntime#execute} 产出；application 映射 REST DTO。
 */
public final class AgentRuntimeResult {

    /** 助手/模型输出文本。 */
    private final String text;
    /** Token 统计（如 input/output/total）。 */
    private final Map<String, Integer> tokens;
    /** 实际使用的模型标识。 */
    private final String model;

    /**
     * @param text   输出，null → ""
     * @param tokens 统计，null → 空映射
     * @param model  模型名，可为 null
     */
    public AgentRuntimeResult(String text, Map<String, Integer> tokens, String model) {
        this.text = text != null ? text : "";
        this.tokens = tokens == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(tokens));
        this.model = model;
    }

    /** @return 输出文本 */
    public String getText() {
        return text;
    }

    /** @return 不可变 token 映射 */
    public Map<String, Integer> getTokens() {
        return tokens;
    }

    /** @return 模型标识 */
    public String getModel() {
        return model;
    }
}
