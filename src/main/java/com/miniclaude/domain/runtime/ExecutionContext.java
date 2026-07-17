package com.miniclaude.domain.runtime;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 一次运行的显式安全边界上下文。
 *
 * <p>上下文不可变，显式携带工作区、租户、会话、运行和追踪标识，避免通过 JVM
 * 全局状态传递边界信息。它只描述边界，不证明工作区已获授权；基础设施仍须执行沙箱检查。
 */
public final class ExecutionContext {

    private final Path workspace;
    private final String tenantId;
    private final String sessionId;
    private final String runId;
    private final String traceId;

    /**
     * 构造规范化的执行边界。
     *
     * <p>工作区不能为空，所有标识必须含非空白文本；失败时抛出
     * {@link NullPointerException} 或 {@link IllegalArgumentException}。路径只进行绝对化和
     * 词法规范化，不访问文件系统、也不解析符号链接。实例不可变，可安全并发共享。
     */
    public ExecutionContext(
            Path workspace,
            String tenantId,
            String sessionId,
            String runId,
            String traceId) {
        this.workspace = Objects.requireNonNull(workspace, "workspace")
                .toAbsolutePath()
                .normalize();
        this.tenantId = requireText(tenantId, "tenantId");
        this.sessionId = requireText(sessionId, "sessionId");
        this.runId = requireText(runId, "runId");
        this.traceId = requireText(traceId, "traceId");
    }

    public Path getWorkspace() {
        return workspace;
    }

    public String getTenantId() {
        return tenantId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getRunId() {
        return runId;
    }

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
