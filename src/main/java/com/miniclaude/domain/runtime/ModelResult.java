package com.miniclaude.domain.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 供应商无关的模型调用结果 DTO。
 * <p>
 * <b>为何放在 domain：</b>规范化 LLM 输出形状。
 * <p>
 * <b>不变量：</b>text 非 null；tokens 不可变副本。
 * <p>
 * <b>边界：</b>不表达 stop_reason、finish_reason、moderation 结果（上层扩展）。
 */
public final class ModelResult {

    /** 模型生成文本。 */
    private final String text;
    /** Token 用量。 */
    private final Map<String, Integer> tokens;

    public ModelResult(String text, Map<String, Integer> tokens) {
        this.text = text != null ? text : "";
        this.tokens = tokens == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(tokens));
    }

    /** @return 生成文本 */
    public String getText() {
        return text;
    }

    /** @return token 统计 */
    public Map<String, Integer> getTokens() {
        return tokens;
    }
}
