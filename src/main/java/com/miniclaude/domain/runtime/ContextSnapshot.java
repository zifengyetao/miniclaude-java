package com.miniclaude.domain.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可由 {@link ContextStore} 持久化的运行时状态快照。
 * <p>
 * <b>为何放在 domain：</b>ReAct 循环中间状态（工具历史、变量）的存储形状属于运行时领域语言。
 * <p>
 * <b>不变量：</b>顶层 Map 防御性复制为不可变；<b>不深拷贝</b> value 对象（敏感数据过滤由 store 负责）。
 * <p>
 * <b>边界：</b>infrastructure 内存/Redis/JDBC 实现序列化与加密。
 */
public final class ContextSnapshot {

    /** 键值状态（引擎私有 schema）。 */
    private final Map<String, Object> values;

    /**
     * @param values 状态映射，null → 空不可变映射
     */
    public ContextSnapshot(Map<String, Object> values) {
        this.values = values == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    /** @return 不可变状态映射 */
    public Map<String, Object> getValues() {
        return values;
    }
}
