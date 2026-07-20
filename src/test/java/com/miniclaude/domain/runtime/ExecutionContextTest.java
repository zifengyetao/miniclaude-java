package com.miniclaude.domain.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 验证执行上下文的路径规范化和边界标识不变量。
 *
 * <p>测试仅覆盖词法路径处理；工作区授权和真实路径检查属于基础设施测试范围。
 */
class ExecutionContextTest {

    /** 工作区路径 normalize 为绝对路径；tenant/session/run/trace 去首尾空白。 */
    @Test
    void normalizesWorkspaceAndCarriesAllBoundaryIdentifiers() {
        ExecutionContext context = new ExecutionContext(
                Paths.get("."),
                " tenant-a ",
                " session-a ",
                " run-a ",
                " trace-a ");

        assertThat(context.getWorkspace()).isAbsolute().isEqualTo(
                Paths.get("").toAbsolutePath().normalize());
        assertThat(context.getTenantId()).isEqualTo("tenant-a");
        assertThat(context.getSessionId()).isEqualTo("session-a");
        assertThat(context.getRunId()).isEqualTo("run-a");
        assertThat(context.getTraceId()).isEqualTo("trace-a");
    }

    /** traceId 为空字符串时构造器应拒绝，避免分布式追踪链断裂。 */
    @Test
    void rejectsMissingBoundaryIdentifiers() {
        assertThatThrownBy(() -> new ExecutionContext(
                Paths.get("."), "tenant", "session", "run", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("traceId is required");
    }
}
