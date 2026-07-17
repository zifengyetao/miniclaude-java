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

    /** 一次已持久化节点转换的只读结果。 */
    public static final class Transition {
        private final String completedNode;
        private final String nextNode;
        private final boolean terminal;
        private final AgentRun run;
        private final Map<String, Object> state;

        public Transition(String completedNode, String nextNode, boolean terminal, AgentRun run,
                          Map<String, Object> state) {
            this.completedNode = completedNode; this.nextNode = nextNode; this.terminal = terminal;
            this.run = run; this.state = state;
        }
        public String getCompletedNode() { return completedNode; }
        public String getNextNode() { return nextNode; }
        public boolean isTerminal() { return terminal; }
        public AgentRun getRun() { return run; }
        public Map<String, Object> getState() { return state; }
    }
}
