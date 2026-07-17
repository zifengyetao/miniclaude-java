package com.miniclaude.domain.runtime;

import java.util.Optional;

/**
 * 运行时上下文快照的持久化端口。
 *
 * <p>端口以完整 {@link ExecutionContext} 作为隔离键，不规定存储介质、事务或并发覆盖策略；
 * 实现不得把不同租户或运行的快照混用。
 */
public interface ContextStore {

    Optional<ContextSnapshot> load(ExecutionContext context);

    void save(ExecutionContext context, ContextSnapshot snapshot);

    void delete(ExecutionContext context);
}
