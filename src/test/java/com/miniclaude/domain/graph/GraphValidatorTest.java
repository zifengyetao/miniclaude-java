package com.miniclaude.domain.graph;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class GraphValidatorTest {

    private final GraphValidator validator = new GraphValidator();

    @Test
    void acceptsGovernedLinearGraph() {
        GraphSpec spec = new GraphSpec(
                "data-analysis",
                "1.0.0",
                "authorize",
                Arrays.asList(
                        new GraphSpec.Node("authorize", GraphSpec.NodeType.POLICY, "data-access@1"),
                        new GraphSpec.Node("query", GraphSpec.NodeType.TOOL, "warehouse.query@1"),
                        new GraphSpec.Node("verify", GraphSpec.NodeType.VERIFIER, "result-check@1"),
                        new GraphSpec.Node("finish", GraphSpec.NodeType.TERMINAL, "")),
                Arrays.asList(
                        new GraphSpec.Edge("authorize", "query", "state.allowed"),
                        new GraphSpec.Edge("query", "verify", ""),
                        new GraphSpec.Edge("verify", "finish", "state.verdict == 'pass'")),
                new GraphSpec.Limits(20, 1, new BigDecimal("5.00")));

        GraphValidationResult result = validator.validate(spec);

        assertThat(result.getErrors()).isEmpty();
    }

    @Test
    void rejectsUnboundedCycleAndUnreachableNode() {
        GraphSpec spec = new GraphSpec(
                "invalid",
                "1.0.0",
                "plan",
                Arrays.asList(
                        new GraphSpec.Node("plan", GraphSpec.NodeType.PLANNER, "planner@1"),
                        new GraphSpec.Node("execute", GraphSpec.NodeType.EXECUTOR, "executor@1"),
                        new GraphSpec.Node("orphan", GraphSpec.NodeType.TERMINAL, "")),
                Arrays.asList(
                        new GraphSpec.Edge("plan", "execute", ""),
                        new GraphSpec.Edge("execute", "plan", "state.retry")),
                new GraphSpec.Limits(20, 0, new BigDecimal("5.00")));

        GraphValidationResult result = validator.validate(spec);

        assertThat(result.getErrors())
                .contains("cyclic graph requires a positive maxIterations limit")
                .contains("unreachable node: orphan");
    }
}
