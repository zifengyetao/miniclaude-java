package com.miniclaude.domain.runtime;

import java.util.Optional;

/**
 * 运行时上下文快照持久化 Outbound Port。
 * <p>
 * <b>为何放在 domain：</b>声明「按 ExecutionContext 键存取快照」，不绑定 Redis/JDBC。
 * <p>
 * <b>不变量：</b>不同 tenant/run/session 的快照不得混用；完整 context 作为隔离键。
 * <p>
 * <b>边界：</b>infrastructure 可选实现；Chat 路径可能仅用引擎内存而不调用 store。
 */
public interface ContextStore {

    /**
     * 加载快照。
     *
     * @param context 隔离键
     * @return 存在则返回
     */
    Optional<ContextSnapshot> load(ExecutionContext context);

    /**
     * 保存或覆盖快照（覆盖策略由实现定义）。
     */
    void save(ExecutionContext context, ContextSnapshot snapshot);

    /**
     * 删除快照（会话/run 结束时）。
     */
    void delete(ExecutionContext context);
}
