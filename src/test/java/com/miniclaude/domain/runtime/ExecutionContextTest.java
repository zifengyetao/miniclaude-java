package com.miniclaude.domain.runtime;

import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExecutionContextTest {

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

    @Test
    void rejectsMissingBoundaryIdentifiers() {
        assertThatThrownBy(() -> new ExecutionContext(
                Paths.get("."), "tenant", "session", "run", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("traceId is required");
    }
}
