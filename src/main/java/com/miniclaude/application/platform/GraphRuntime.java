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

/** 仅执行确定性节点，不包含自由 LLM 执行。 */
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
        nextState.put("_lastNode", nodeId);
        nextState.put("_graphVersion", graph.getVersion());
        String next = graph.getEdges().stream()
                .filter(edge -> edge.getFrom().equals(nodeId))
                .filter(edge -> matches(edge.getCondition(), nextState))
                .map(GraphSpec.Edge::getTo)
                .findFirst().orElse(null);
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
