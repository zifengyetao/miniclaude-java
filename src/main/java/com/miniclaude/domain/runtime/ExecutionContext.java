package com.miniclaude.domain.runtime;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 一次运行的显式安全边界上下文（不可变值对象）。
 * <p>
 * <b>为何放在 domain：</b>租户/会话/Run/追踪/工作区是跨 Chat、Runtime、Durable 的公共隔离键，
 * 禁止通过 JVM 全局 ThreadLocal 隐式传递。
 * <p>
 * <b>不变量：</b>
 * <ul>
 *   <li>workspace 非 null，经 {@code toAbsolutePath().normalize()} 规范化（不访问 FS、不解析 symlink）。</li>
 *   <li>tenantId/sessionId/runId/traceId 均为非空白文本。</li>
 * </ul>
 * <p>
 * <b>边界：</b>application 构造；infrastructure 沙箱 {@link SandboxPolicy} 与工作区租约 {@link WorkspaceLease} 强制执行。
 */
public final class ExecutionContext {

    /** Agent 工作区根路径（词法规范化后的绝对路径）。 */
    private final Path workspace;
    /** 租户 ID，多租户隔离。 */
    private final String tenantId;
    /** Chat 会话 ID 或逻辑会话键。 */
    private final String sessionId;
    /** Durable Run ID（Chat 路径可与 sessionId 相同或占位）。 */
    private final String runId;
    /** 分布式追踪 ID（日志/OTel 关联）。 */
    private final String traceId;

    /**
     * 构造规范化的执行边界。
     *
     * @throws NullPointerException     workspace 为 null
     * @throws IllegalArgumentException 标识字段空白
     */
    public ExecutionContext(
            Path workspace,
            String tenantId,
            String sessionId,
            String runId,
            String traceId) {
        // 仅词法规范化，不触碰文件系统，避免构造时 IO 副作用
        this.workspace = Objects.requireNonNull(workspace, "workspace")
                .toAbsolutePath()
                .normalize();
        this.tenantId = requireText(tenantId, "tenantId");
        this.sessionId = requireText(sessionId, "sessionId");
        this.runId = requireText(runId, "runId");
        this.traceId = requireText(traceId, "traceId");
    }

    /** @return 工作区路径 */
    public Path getWorkspace() {
        return workspace;
    }

    /** @return 租户 ID */
    public String getTenantId() {
        return tenantId;
    }

    /** @return 会话 ID */
    public String getSessionId() {
        return sessionId;
    }

    /** @return Run ID */
    public String getRunId() {
        return runId;
    }

    /** @return 追踪 ID */
    public String getTraceId() {
        return traceId;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
