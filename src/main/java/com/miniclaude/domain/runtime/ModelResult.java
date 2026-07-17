package com.miniclaude.domain.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 供应商无关的模型调用结果。
 *
 * <p>本类只规范化生成文本和令牌统计，不表达停止原因、计费或安全判定；令牌映射在
 * 构造时复制，避免调用方后续修改顶层统计。
 */
public final class ModelResult {

    private final String text;
    private final Map<String, Integer> tokens;

    public ModelResult(String text, Map<String, Integer> tokens) {
        this.text = text != null ? text : "";
        this.tokens = tokens == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(tokens));
    }

    public String getText() {
        return text;
    }

    public Map<String, Integer> getTokens() {
        return tokens;
    }
}
