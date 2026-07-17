package com.miniclaude.domain.graph;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 供应商无关且不可变的 Agent 图编排定义。
 *
 * <p>该聚合描述节点、边和运行上限，只承载编排语义，不负责静态校验、持久化或执行。
 * 构造时复制集合并建立节点索引，防止调用方随后修改集合而绕过图边界。
 */
public final class GraphSpec {

    /** 节点的抽象能力类型，不绑定具体模型、工具或工作流引擎。 */
    public enum NodeType {
        DETERMINISTIC,
        LLM,
        PLANNER,
        EXECUTOR,
        TOOL,
        RETRIEVAL,
        POLICY,
        APPROVAL,
        VERIFIER,
        SUBGRAPH,
        WAIT_EVENT,
        HUMAN_TASK,
        TERMINAL
    }

    /** 图中的不可变节点；{@code reference} 由更外层的注册表解释。 */
    public static final class Node {
        private final String id;
        private final NodeType type;
        private final String reference;

        public Node(String id, NodeType type, String reference) {
            this.id = requireText(id, "node.id");
            this.type = Objects.requireNonNull(type, "node.type");
            this.reference = reference == null ? "" : reference.trim();
        }

        public String getId() { return id; }
        public NodeType getType() { return type; }
        public String getReference() { return reference; }
    }

    /** 两个节点之间的有向边；条件字符串在本领域对象中保持为不透明值。 */
    public static final class Edge {
        private final String from;
        private final String to;
        private final String condition;

        public Edge(String from, String to, String condition) {
            this.from = requireText(from, "edge.from");
            this.to = requireText(to, "edge.to");
            this.condition = condition == null ? "" : condition.trim();
        }

        public String getFrom() { return from; }
        public String getTo() { return to; }
        public String getCondition() { return condition; }
    }

    /** 执行器必须遵守的步数、迭代次数和可选费用上限。 */
    public static final class Limits {
        private final int maxSteps;
        private final int maxIterations;
        private final BigDecimal maxCostUsd;

        public Limits(int maxSteps, int maxIterations, BigDecimal maxCostUsd) {
            this.maxSteps = maxSteps;
            this.maxIterations = maxIterations;
            this.maxCostUsd = maxCostUsd;
        }

        public int getMaxSteps() { return maxSteps; }
        public int getMaxIterations() { return maxIterations; }
        public BigDecimal getMaxCostUsd() { return maxCostUsd; }
    }

    private final String name;
    private final String version;
    private final String entryNode;
    private final Map<String, Node> nodes;
    private final List<Edge> edges;
    private final Limits limits;

    /**
     * 创建图定义并对节点建立稳定顺序的索引。
     *
     * <p>名称、版本和入口不能为空，节点标识必须唯一，{@code limits} 必须存在；
     * 更复杂的可达性、边端点和循环约束由 {@link GraphValidator} 负责。重复节点会抛出
     * {@link IllegalArgumentException}。对象构造完成后不可变，适合跨线程只读共享。
     */
    public GraphSpec(
            String name,
            String version,
            String entryNode,
            List<Node> nodes,
            List<Edge> edges,
            Limits limits) {
        this.name = requireText(name, "name");
        this.version = requireText(version, "version");
        this.entryNode = requireText(entryNode, "entryNode");
        Map<String, Node> indexed = new LinkedHashMap<>();
        if (nodes != null) {
            for (Node node : nodes) {
                if (indexed.put(node.getId(), node) != null) {
                    throw new IllegalArgumentException("duplicate node: " + node.getId());
                }
            }
        }
        this.nodes = Collections.unmodifiableMap(indexed);
        this.edges = Collections.unmodifiableList(
                edges == null ? Collections.emptyList() : new ArrayList<>(edges));
        this.limits = Objects.requireNonNull(limits, "limits");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public String getName() { return name; }
    public String getVersion() { return version; }
    public String getEntryNode() { return entryNode; }
    public Map<String, Node> getNodes() { return nodes; }
    public List<Edge> getEdges() { return edges; }
    public Limits getLimits() { return limits; }
}
