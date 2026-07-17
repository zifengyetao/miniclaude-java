package com.miniclaude.domain.runtime;

import java.nio.file.Path;
import java.util.Objects;

/**
 * 一次运行的显式边界上下文。
 *
 * <p>上下文不可变，避免通过 JVM 全局状态隐式传递工作目录或租户信息。
 */
public final class ExecutionContext {

    private final Path workspace;
    private final String tenantId;
    private final String sessionId;
    private final String runId;
    private final String traceId;

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
