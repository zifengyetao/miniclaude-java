package com.miniclaude.domain.runtime;

import java.nio.file.Path;

/**
 * 工作区独占使用权的生命周期句柄（协调原语，非 OS 锁）。
 * <p>
 * <b>为何放在 domain：</b>多 Run 共享工作区时需要显式租约语义，属于运行时领域契约。
 * <p>
 * <b>不变量：</b>
 * <ul>
 *   <li>持有者必须 {@link #close()} 释放；close 应幂等。</li>
 *   <li>租约不替代 {@link SandboxPolicy} 与 OS 文件权限。</li>
 * </ul>
 * <p>
 * <b>边界：</b>infrastructure Provider 实现（内存锁/DB 锁）；application 在 Run 生命周期绑定 acquire/close。
 */
public interface WorkspaceLease extends AutoCloseable {

    /** @return 被租用的工作区路径 */
    Path getWorkspace();

    /** @return 租约唯一 ID（审计/调试） */
    String getLeaseId();

    /** 释放租约；幂等。 */
    @Override void close();

    /**
     * 创建租约的基础设施 Outbound Port。
     */
    interface Provider {
        /**
         * 为 Run 申请工作区独占权。
         *
         * @param workspace 须已通过 SandboxPolicy
         * @param runId     关联 Run
         * @return 租约句柄，用完须 close
         * @throws SecurityException 未授权或冲突（实现定义）
         */
        WorkspaceLease acquire(Path workspace, String runId);
    }
}
