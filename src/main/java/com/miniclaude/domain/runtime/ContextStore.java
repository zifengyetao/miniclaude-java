package com.miniclaude.domain.runtime;

import java.util.Optional;

/**
 * 运行时上下文持久化端口。
 */
public interface ContextStore {

    Optional<ContextSnapshot> load(ExecutionContext context);

    void save(ExecutionContext context, ContextSnapshot snapshot);

    void delete(ExecutionContext context);
}
