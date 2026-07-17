package com.miniclaude.domain.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可由不同 {@link ContextStore} 实现持久化的运行时状态快照。
 *
 * <p>本类对顶层映射做防御性复制并暴露只读视图，但不深拷贝其中的值；敏感信息的
 * 筛除、序列化兼容性和加密属于存储实现的边界。
 */
public final class ContextSnapshot {

    private final Map<String, Object> values;

    public ContextSnapshot(Map<String, Object> values) {
        this.values = values == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public Map<String, Object> getValues() {
        return values;
    }
}
