package com.miniclaude.infrastructure.runtime;

import com.miniclaude.domain.runtime.ExecutionContext;
import com.miniclaude.domain.runtime.HarnessModelGateway;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlledHarnessModelGatewayTest {

    @Test
    void freezesVersionedRouteAfterFirstRegistration() {
        ControlledHarnessModelGateway gateway = new ControlledHarnessModelGateway();
        HarnessModelGateway first = request -> HarnessModelGateway.ModelTurn.finalText("first");
        gateway.register("model@1.0.0", first);

        assertThatThrownBy(() -> gateway.register(
                "model@1.0.0", request -> HarnessModelGateway.ModelTurn.finalText("replacement")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already registered");

        HarnessModelGateway.ModelTurn result = gateway.next(new HarnessModelGateway.ModelTurnRequest(
                new ExecutionContext(Paths.get(""), "tenant", "session", "run", "trace"),
                "model@1.0.0", "profile", 1, "prompt"));
        assertThat(result.getText()).isEqualTo("first");
    }
}
