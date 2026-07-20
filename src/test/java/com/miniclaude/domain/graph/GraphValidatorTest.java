package com.miniclaude.domain.graph;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 覆盖图校验器对受治理线性图和无界循环等关键安全约束的判断。
 *
 * <p>测试使用纯领域对象，不涉及条件解析、持久化或节点执行。
 */
class GraphValidatorTest {

    private final GraphValidator validator = new GraphValidator();

    /** 含 POLICY/TOOL/VERIFIER/TERMINAL 的线性受治理图应无 errors。 */
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

        // 同时保留策略、工具和验证节点，证明治理节点不会被静态校验误判为不可执行。
        GraphValidationResult result = validator.validate(spec);

        assertThat(result.getErrors()).isEmpty();
    }

    /** 无 maxIterations 的环与 unreachable 节点应同时出现在 errors 列表。 */
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

        // 一个样例故意叠加环和孤立节点，验证校验器会汇总而非短路首个结构错误。
        GraphValidationResult result = validator.validate(spec);

        assertThat(result.getErrors())
                .contains("cyclic graph requires a positive maxIterations limit")
                .contains("unreachable node: orphan");
    }
}
