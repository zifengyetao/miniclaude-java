package com.miniclaude.domain.graph;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** 供应商无关的 Agent 图编排定义。 */
public final class GraphSpec {

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
