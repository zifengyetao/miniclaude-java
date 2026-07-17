package com.miniclaude.domain.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行时执行结果。
 */
public final class AgentRuntimeResult {

    private final String text;
    private final Map<String, Integer> tokens;
    private final String model;

    public AgentRuntimeResult(String text, Map<String, Integer> tokens, String model) {
        this.text = text != null ? text : "";
        this.tokens = tokens == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(tokens));
        this.model = model;
    }

    public String getText() {
        return text;
    }

    public Map<String, Integer> getTokens() {
        return tokens;
    }

    public String getModel() {
        return model;
    }
}
