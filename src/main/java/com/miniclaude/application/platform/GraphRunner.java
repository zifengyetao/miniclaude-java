package com.miniclaude.application.platform;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import com.miniclaude.domain.durable.DurableOrchestrator;
import com.miniclaude.domain.durable.DurableStores;
import com.miniclaude.domain.durable.RunCheckpoint;
import com.miniclaude.domain.graph.GraphSpec;
import com.miniclaude.domain.graph.GraphValidationResult;
import com.miniclaude.domain.graph.GraphValidator;
import com.miniclaude.domain.platform.AgentRun;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 供应商无关的同步 Graph 执行器。
 *
 * <p>GraphRunner 解释节点与边，业务 {@link NodeExecutor} 只负责单节点计算。每个节点完成后，
 * Runner 将下一节点游标、逻辑 attempt、输入/输出哈希与图版本写入 checkpoint；恢复时只读取
 * checkpoint，不依赖进程内调用栈。外部副作用的 exactly-once 不由本类保证，后续必须由工具
 * 幂等键和 effect ledger 约束。</p>
 */
@Service
public class GraphRunner {
    private static final String NEXT_NODE = "_nextNode";
    private final DurableOrchestrator orchestrator;
    private final DurableStores.CheckpointStore checkpoints;
    private final GraphTerminalCommitter terminalCommitter;
    private final GraphValidator validator = new GraphValidator();
    private final Gson gson = new Gson();

    public GraphRunner(DurableOrchestrator orchestrator, DurableStores.CheckpointStore checkpoints,
                       GraphTerminalCommitter terminalCommitter) {
        this.orchestrator = orchestrator;
        this.checkpoints = checkpoints;
        this.terminalCommitter = terminalCommitter;
    }

    /** 从 entry node 开始执行，直到终态或人工审批暂停。 */
    public Result start(GraphSpec graph, String tenantId, String runId,
                        Map<String, Object> initialState, NodeExecutor executor) {
        validate(graph);
        Map<String, Object> state = initialState == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(initialState);
        return execute(graph, tenantId, runId, graph.getEntryNode(), state, executor);
    }

    /** 从最新 checkpoint 保存的 next node 恢复，不重复已经完成的节点。 */
    public Result resume(GraphSpec graph, String tenantId, String runId, NodeExecutor executor) {
        Map<String, Object> state = loadCheckpointState(graph, tenantId, runId);
        Object next = state.get(NEXT_NODE);
        if (next == null || next.toString().trim().isEmpty()) {
            throw new IllegalStateException("checkpoint has no resumable node");
        }
        return execute(graph, tenantId, runId, next.toString(), state, executor);
    }

    /** 加载并校验最新 Graph checkpoint，供恢复前的场景/审批绑定检查使用。 */
    public Map<String, Object> loadCheckpointState(GraphSpec graph, String tenantId, String runId) {
        validate(graph);
        RunCheckpoint checkpoint = checkpoints.latest(tenantId, runId)
                .orElseThrow(() -> new IllegalStateException("graph checkpoint missing"));
        if (!sha256(checkpoint.getState()).equals(checkpoint.getStateHash())) {
            throw new IllegalStateException("checkpoint state hash mismatch");
        }
        JsonObject persisted = gson.fromJson(checkpoint.getState(), JsonObject.class);
        JsonElement persistedOutputHash = persisted.get("_outputHash");
        removeRuntimeMetadata(persisted);
        if (persistedOutputHash == null
                || !persistedOutputHash.getAsString().equals(sha256(canonical(persisted)))) {
            throw new IllegalStateException("checkpoint output hash mismatch");
        }
        Map<String, Object> state = gson.fromJson(checkpoint.getState(),
                new TypeToken<Map<String, Object>>() { }.getType());
        if (!graph.getName().equals(state.get("_graphName"))
                || !graph.getVersion().equals(state.get("_graphVersion"))) {
            throw new IllegalStateException("checkpoint graph version mismatch");
        }
        return Collections.unmodifiableMap(new LinkedHashMap<>(state));
    }

    private Result execute(GraphSpec graph, String tenantId, String runId, String firstNode,
                           Map<String, Object> initialState, NodeExecutor executor) {
        String nodeId = firstNode;
        Map<String, Object> state = new LinkedHashMap<>(initialState);
        while (nodeId != null) {
            GraphSpec.Node node = graph.getNodes().get(nodeId);
            if (node == null) throw new IllegalStateException("node not found: " + nodeId);

            int attempt = nextAttempt(tenantId, runId, nodeId);
            String inputHash = hash(businessState(state));
            NodeResult nodeResult = executor.execute(new NodeContext(
                    tenantId, runId, graph, node, attempt, Collections.unmodifiableMap(state)));
            if (nodeResult == null) throw new IllegalStateException("node returned null: " + nodeId);

            Map<String, Object> nextState = new LinkedHashMap<>(state);
            nextState.putAll(nodeResult.getUpdates());
            String nextNode = selectNext(graph, node, nextState);
            String outputHash = hash(businessState(nextState));
            nextState.put("_graphName", graph.getName());
            nextState.put("_graphVersion", graph.getVersion());
            nextState.put("_completedNode", nodeId);
            nextState.put(NEXT_NODE, nextNode);
            nextState.put("_attempt", attempt);
            nextState.put("_inputHash", inputHash);
            nextState.put("_outputHash", outputHash);

            String key = "graph:" + runId + ":" + nodeId + ":attempt:" + attempt;
            String serializedState = gson.toJson(nextState);
            AgentRun run;
            if (nodeResult.getApproval() != null) {
                Approval approval = nodeResult.getApproval();
                run = orchestrator.recordStepAndAwaitApproval(tenantId, runId, nodeId,
                        serializedState, nodeResult.getCostUsd(), approval.getActionType(),
                        approval.getActionParameters(), approval.getTtl(), key);
                if (run.getStatus() != AgentRun.Status.WAITING_APPROVAL) {
                    throw new IllegalStateException("approval node did not suspend run");
                }
                return new Result(run, nodeId, nextNode, true, false, nextState);
            }
            if (node.getType() == GraphSpec.NodeType.TERMINAL) {
                AgentRun completed = terminalCommitter.commit(tenantId, runId, nodeId,
                        serializedState, nodeResult.getCostUsd(), key, nodeResult.getTerminalAction());
                return new Result(completed, nodeId, null, false, true, nextState);
            }
            if (nodeResult.getTerminalAction() != null) {
                throw new IllegalStateException("terminal action returned by non-terminal node: " + nodeId);
            }
            run = orchestrator.recordStep(tenantId, runId, nodeId, serializedState,
                    nodeResult.getCostUsd(), key);
            if (run.getStatus() != AgentRun.Status.RUNNING) {
                throw new IllegalStateException("run stopped after node " + nodeId + ": " + run.getStatus());
            }
            if (nextNode == null) {
                throw new IllegalStateException("non-terminal node has no matching edge: " + nodeId);
            }
            state = nextState;
            nodeId = nextNode;
        }
        throw new IllegalStateException("graph stopped without terminal node");
    }

    private String selectNext(GraphSpec graph, GraphSpec.Node node, Map<String, Object> state) {
        List<String> matches = new ArrayList<>();
        for (GraphSpec.Edge edge : graph.getEdges()) {
            if (edge.getFrom().equals(node.getId()) && matches(edge.getCondition(), state)) {
                matches.add(edge.getTo());
            }
        }
        if (matches.size() > 1) {
            throw new IllegalStateException("ambiguous graph edges from " + node.getId() + ": " + matches);
        }
        return matches.isEmpty() ? null : matches.get(0);
    }

    private boolean matches(String condition, Map<String, Object> state) {
        if (condition == null || condition.trim().isEmpty() || "always".equals(condition)) return true;
        int separator = condition.indexOf('=');
        if (separator < 1) return false;
        Object actual = state.get(condition.substring(0, separator).trim());
        return actual != null && actual.toString().equals(condition.substring(separator + 1).trim());
    }

    private int nextAttempt(String tenantId, String runId, String nodeId) {
        long completed = checkpoints.findCheckpoints(tenantId, runId).stream()
                .filter(checkpoint -> checkpoint.getStepId().equals(nodeId))
                .count();
        if (completed >= Integer.MAX_VALUE) throw new IllegalStateException("attempt overflow");
        return (int) completed + 1;
    }

    private void validate(GraphSpec graph) {
        GraphValidationResult result = validator.validate(graph);
        if (!result.isValid()) throw new IllegalArgumentException("invalid graph: " + result.getErrors());
    }

    private Map<String, Object> businessState(Map<String, Object> state) {
        Map<String, Object> business = new LinkedHashMap<>(state);
        business.remove("_graphName");
        business.remove("_graphVersion");
        business.remove("_completedNode");
        business.remove(NEXT_NODE);
        business.remove("_attempt");
        business.remove("_inputHash");
        business.remove("_outputHash");
        return business;
    }

    private void removeRuntimeMetadata(JsonObject state) {
        state.remove("_graphName");
        state.remove("_graphVersion");
        state.remove("_completedNode");
        state.remove(NEXT_NODE);
        state.remove("_attempt");
        state.remove("_inputHash");
        state.remove("_outputHash");
    }

    private String hash(Object value) {
        return sha256(canonical(gson.toJsonTree(value)));
    }

    private String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return hex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("cannot hash graph state", e);
        }
    }

    private String canonical(JsonElement element) {
        if (element == null || element.isJsonNull() || element.isJsonPrimitive()) {
            return element == null ? "null" : element.toString();
        }
        if (element.isJsonArray()) {
            JsonArray array = element.getAsJsonArray();
            StringBuilder result = new StringBuilder("[");
            for (int i = 0; i < array.size(); i++) {
                if (i > 0) result.append(',');
                result.append(canonical(array.get(i)));
            }
            return result.append(']').toString();
        }
        JsonObject object = element.getAsJsonObject();
        List<Map.Entry<String, JsonElement>> entries = new ArrayList<>(object.entrySet());
        entries.sort(Comparator.comparing(Map.Entry::getKey));
        StringBuilder result = new StringBuilder("{");
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) result.append(',');
            Map.Entry<String, JsonElement> entry = entries.get(i);
            result.append(gson.toJson(entry.getKey())).append(':').append(canonical(entry.getValue()));
        }
        return result.append('}').toString();
    }

    private static String hex(byte[] bytes) {
        StringBuilder result = new StringBuilder(bytes.length * 2);
        for (byte value : bytes) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    /** 业务节点执行端口；实现不得自行推进 Graph 游标。 */
    public interface NodeExecutor {
        NodeResult execute(NodeContext context);
    }

    /** 仅允许写入同一事务数据库资源的终态动作。 */
    @FunctionalInterface
    public interface TerminalAction {
        void run();
    }

    /** 单节点执行所需的不可变上下文。 */
    public static final class NodeContext {
        private final String tenantId;
        private final String runId;
        private final GraphSpec graph;
        private final GraphSpec.Node node;
        private final int attempt;
        private final Map<String, Object> state;

        NodeContext(String tenantId, String runId, GraphSpec graph, GraphSpec.Node node,
                    int attempt, Map<String, Object> state) {
            this.tenantId = tenantId;
            this.runId = runId;
            this.graph = graph;
            this.node = node;
            this.attempt = attempt;
            this.state = state;
        }

        public String getTenantId() { return tenantId; }
        public String getRunId() { return runId; }
        public GraphSpec getGraph() { return graph; }
        public GraphSpec.Node getNode() { return node; }
        public int getAttempt() { return attempt; }
        public Map<String, Object> getState() { return state; }
    }

    /** 节点输出：状态增量、成本，以及可选的审批暂停命令。 */
    public static final class NodeResult {
        private final Map<String, Object> updates;
        private final BigDecimal costUsd;
        private final Approval approval;
        private final TerminalAction terminalAction;

        private NodeResult(Map<String, Object> updates, BigDecimal costUsd, Approval approval,
                           TerminalAction terminalAction) {
            this.updates = updates == null ? Collections.emptyMap() : new LinkedHashMap<>(updates);
            this.costUsd = costUsd == null ? BigDecimal.ZERO : costUsd;
            this.approval = approval;
            this.terminalAction = terminalAction;
        }

        public static NodeResult continueWith(Map<String, Object> updates) {
            return new NodeResult(updates, BigDecimal.ZERO, null, null);
        }

        public static NodeResult continueWith(Map<String, Object> updates, BigDecimal costUsd) {
            return new NodeResult(updates, costUsd, null, null);
        }

        public static NodeResult terminalWith(Map<String, Object> updates,
                                              TerminalAction terminalAction) {
            return new NodeResult(updates, BigDecimal.ZERO, null, terminalAction);
        }

        public static NodeResult awaitApproval(Map<String, Object> updates, String actionType,
                                               String actionParameters, Duration ttl) {
            return new NodeResult(updates, BigDecimal.ZERO,
                    new Approval(actionType, actionParameters, ttl), null);
        }

        public Map<String, Object> getUpdates() { return Collections.unmodifiableMap(updates); }
        public BigDecimal getCostUsd() { return costUsd; }
        public Approval getApproval() { return approval; }
        public TerminalAction getTerminalAction() { return terminalAction; }
    }

    /** 持久审批命令。 */
    public static final class Approval {
        private final String actionType;
        private final String actionParameters;
        private final Duration ttl;

        Approval(String actionType, String actionParameters, Duration ttl) {
            if (actionType == null || actionType.trim().isEmpty()) {
                throw new IllegalArgumentException("actionType required");
            }
            if (ttl == null || ttl.isZero() || ttl.isNegative()) {
                throw new IllegalArgumentException("approval ttl must be positive");
            }
            this.actionType = actionType;
            this.actionParameters = actionParameters == null ? "{}" : actionParameters;
            this.ttl = ttl;
        }

        public String getActionType() { return actionType; }
        public String getActionParameters() { return actionParameters; }
        public Duration getTtl() { return ttl; }
    }

    /** 一次执行批次的结果；暂停与完成互斥。 */
    public static final class Result {
        private final AgentRun run;
        private final String completedNode;
        private final String nextNode;
        private final boolean suspended;
        private final boolean completed;
        private final Map<String, Object> state;

        Result(AgentRun run, String completedNode, String nextNode, boolean suspended,
               boolean completed, Map<String, Object> state) {
            this.run = run;
            this.completedNode = completedNode;
            this.nextNode = nextNode;
            this.suspended = suspended;
            this.completed = completed;
            this.state = new LinkedHashMap<>(state);
        }

        public AgentRun getRun() { return run; }
        public String getCompletedNode() { return completedNode; }
        public String getNextNode() { return nextNode; }
        public boolean isSuspended() { return suspended; }
        public boolean isCompleted() { return completed; }
        public Map<String, Object> getState() { return Collections.unmodifiableMap(state); }
    }
}
