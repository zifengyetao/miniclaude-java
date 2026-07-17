package com.miniclaude.domain.runtime;

import java.nio.file.Path;

/**
 * 工作区独占使用权的生命周期句柄。
 *
 * <p>租约只表达协调所有权，不替代路径授权或操作系统权限检查。持有者必须关闭租约；
 * 实现应使关闭操作幂等，并定义同一工作区并发申请时的拒绝策略。
 */
public interface WorkspaceLease extends AutoCloseable {
    Path getWorkspace();
    String getLeaseId();
    @Override void close();

    /** 创建租约的基础设施端口。 */
    interface Provider {
        /**
         * 为运行申请工作区。工作区必须先通过实现的安全策略；冲突或未授权时抛出异常。
         */
        WorkspaceLease acquire(Path workspace, String runId);
    }
}
