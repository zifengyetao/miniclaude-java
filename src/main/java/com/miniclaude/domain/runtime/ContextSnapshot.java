package com.miniclaude.domain.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可由不同 ContextStore 实现持久化的运行时状态快照。
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
