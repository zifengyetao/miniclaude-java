package com.miniclaude.domain.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 模型调用结果。
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
