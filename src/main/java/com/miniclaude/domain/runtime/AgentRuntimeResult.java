package com.miniclaude.domain.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 运行时执行结果的不可变传输对象。
 *
 * <p>结果统一文本、令牌统计和模型标识，隔离上层与具体引擎返回结构；令牌映射会被
 * 防御性复制，缺失文本和统计分别规范化为空字符串、空映射。
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
