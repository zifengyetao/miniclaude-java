package com.miniclaude.infrastructure.runtime;

import com.miniclaude.domain.runtime.ToolResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlledToolGatewayTest {
    @Test
    void preventsRuntimeReplacementOfRegisteredToolRoute() {
        ControlledToolGateway gateway = new ControlledToolGateway();
        gateway.register("safe_tool", request -> new ToolResult(true, "first"));

        assertThatThrownBy(() -> gateway.register(
                "safe_tool", request -> new ToolResult(true, "replacement")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered");
    }
}
