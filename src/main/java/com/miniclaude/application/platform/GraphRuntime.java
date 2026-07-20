package com.miniclaude.application.platform;

import com.google.gson.Gson;
import com.miniclaude.domain.durable.DurableOrchestrator;
import com.miniclaude.domain.graph.GraphSpec;
import com.miniclaude.domain.graph.GraphValidationResult;
import com.miniclaude.domain.graph.GraphValidator;
import com.miniclaude.domain.platform.AgentRun;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 确定性图节点运行时，不执行自由 LLM 或其他外部副作用。
 *
 * <p>节点完成后通过持久编排端口记录状态和 checkpoint；调用方必须为一次逻辑步骤提供
 * 稳定幂等键，才能在请求重试或进程恢复时避免重复推进。需要外部执行器的节点类型在此
 * fail-closed，防止误把未执行的副作用记为已完成。</p>
 */
@Service
public class GraphRuntime {
    private final DurableOrchestrator orchestrator;
    private final GraphValidator validator = new GraphValidator();
    private final Gson gson = new Gson();

    public GraphRuntime(DurableOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    /**
     * 执行单个确定性图节点并持久化步骤。
     *
     * @param graph           完整图定义（执行前重新校验）
     * @param tenantId        租户
     * @param runId           持久 Run ID
     * @param nodeId          待执行节点
     * @param state           当前状态快照；null 时视为空 Map
     * @param idempotencyKey  稳定幂等键，重试/恢复时必传同一值
     * @return 含下一节点、是否终态、更新后 Run 与状态的 {@link Transition}
     * @throws IllegalArgumentException 图校验失败或节点不存在
     * @throws IllegalStateException 节点类型需外部执行器（LLM/TOOL 等），本运行时 fail-closed
     * @implNote 副作用：{@code orchestrator.recordStep} 写入 checkpoint；不修改入参 state 对象
     */
    public Transition executeNode(GraphSpec graph, String tenantId, String runId, String nodeId,
                                  Map<String, Object> state, String idempotencyKey) {
        // 执行前重新验证图和节点类型，避免持久化一个无法由当前图定义解释的恢复点。
        GraphValidationResult validation = validator.validate(graph);
        if (!validation.isValid()) throw new IllegalArgumentException(validation.getErrors().toString());
        GraphSpec.Node node = graph.getNodes().get(nodeId);
        if (node == null) throw new IllegalArgumentException("node not found: " + nodeId);
        if (node.getType() != GraphSpec.NodeType.DETERMINISTIC
                && node.getType() != GraphSpec.NodeType.POLICY
                && node.getType() != GraphSpec.NodeType.VERIFIER
                && node.getType() != GraphSpec.NodeType.TERMINAL) {
            throw new IllegalStateException("node type requires an external executor: " + node.getType());
        }
        Map<String, Object> nextState = state == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(state);
        // 复制状态后写入恢复元数据，不修改调用方对象；图版本让 checkpoint 可追溯到定义。
        nextState.put("_lastNode", nodeId);
        nextState.put("_graphVersion", graph.getVersion());
        String next = graph.getEdges().stream()
                .filter(edge -> edge.getFrom().equals(nodeId))
                .filter(edge -> matches(edge.getCondition(), nextState))
                .map(GraphSpec.Edge::getTo)
                .findFirst().orElse(null);
        // 先确定下一节点，再将完整状态交给编排器事务性记录；持久化失败时不返回成功转换。
        AgentRun run = orchestrator.recordStep(tenantId, runId, nodeId, gson.toJson(nextState),
                BigDecimal.ZERO, idempotencyKey);
        return new Transition(nodeId, next, node.getType() == GraphSpec.NodeType.TERMINAL, run,
                nextState);
    }

    private boolean matches(String condition, Map<String, Object> state) {
        if (condition == null || condition.trim().isEmpty() || "always".equals(condition)) return true;
        int separator = condition.indexOf('=');
        if (separator < 1) return false;
        Object actual = state.get(condition.substring(0, separator).trim());
        return actual != null && actual.toString().equals(condition.substring(separator + 1).trim());
    }

    /** 一次已持久化节点转换的只读结果快照。 */
    public static final class Transition {
        /** 刚完成的节点 ID */
        private final String completedNode;
        /** 边条件匹配后的下一节点 ID；无匹配出边时为 null */
        private final String nextNode;
        /** 完成节点是否为 TERMINAL 类型 */
        private final boolean terminal;
        /** 记录步骤后的 Run 快照 */
        private final AgentRun run;
        /** 含 {@code _lastNode}、{@code _graphVersion} 等元数据的新状态副本 */
        private final Map<String, Object> state;

        public Transition(String completedNode, String nextNode, boolean terminal, AgentRun run,
                          Map<String, Object> state) {
            this.completedNode = completedNode; this.nextNode = nextNode; this.terminal = terminal;
            this.run = run; this.state = state;
        }
        /** @return 已完成节点 ID */
        public String getCompletedNode() { return completedNode; }
        /** @return 下一节点 ID，可能为 null */
        public String getNextNode() { return nextNode; }
        /** @return 是否到达终态节点 */
        public boolean isTerminal() { return terminal; }
        /** @return 持久化步骤后的 Run */
        public AgentRun getRun() { return run; }
        /** @return 不可变语义的新状态 Map（调用方不应原地修改） */
        public Map<String, Object> getState() { return state; }
    }
}
