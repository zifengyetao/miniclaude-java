package com.miniclaude.interfaces.rest.dto;

import com.miniclaude.domain.graph.GraphSpec;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class ValidateGraphRequest {

    @NotBlank
    private String name;
    @NotBlank
    private String version;
    @NotBlank
    private String entryNode;
    @Valid
    @NotEmpty
    private List<NodeRequest> nodes = new ArrayList<>();
    @Valid
    private List<EdgeRequest> edges = new ArrayList<>();
    @Valid
    @NotNull
    private LimitsRequest limits;

    public GraphSpec toGraphSpec() {
        return new GraphSpec(
                name,
                version,
                entryNode,
                nodes.stream().map(NodeRequest::toNode).collect(Collectors.toList()),
                edges.stream().map(EdgeRequest::toEdge).collect(Collectors.toList()),
                limits.toLimits());
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public String getEntryNode() { return entryNode; }
    public void setEntryNode(String entryNode) { this.entryNode = entryNode; }
    public List<NodeRequest> getNodes() { return nodes; }
    public void setNodes(List<NodeRequest> nodes) { this.nodes = nodes; }
    public List<EdgeRequest> getEdges() { return edges; }
    public void setEdges(List<EdgeRequest> edges) { this.edges = edges; }
    public LimitsRequest getLimits() { return limits; }
    public void setLimits(LimitsRequest limits) { this.limits = limits; }

    public static class NodeRequest {
        @NotBlank
        private String id;
        @NotNull
        private GraphSpec.NodeType type;
        private String reference;

        GraphSpec.Node toNode() { return new GraphSpec.Node(id, type, reference); }
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public GraphSpec.NodeType getType() { return type; }
        public void setType(GraphSpec.NodeType type) { this.type = type; }
        public String getReference() { return reference; }
        public void setReference(String reference) { this.reference = reference; }
    }

    public static class EdgeRequest {
        @NotBlank
        private String from;
        @NotBlank
        private String to;
        private String condition;

        GraphSpec.Edge toEdge() { return new GraphSpec.Edge(from, to, condition); }
        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }
        public String getTo() { return to; }
        public void setTo(String to) { this.to = to; }
        public String getCondition() { return condition; }
        public void setCondition(String condition) { this.condition = condition; }
    }

    public static class LimitsRequest {
        @Positive
        private int maxSteps;
        private int maxIterations;
        @Positive
        private BigDecimal maxCostUsd;

        GraphSpec.Limits toLimits() {
            return new GraphSpec.Limits(maxSteps, maxIterations, maxCostUsd);
        }
        public int getMaxSteps() { return maxSteps; }
        public void setMaxSteps(int maxSteps) { this.maxSteps = maxSteps; }
        public int getMaxIterations() { return maxIterations; }
        public void setMaxIterations(int maxIterations) { this.maxIterations = maxIterations; }
        public BigDecimal getMaxCostUsd() { return maxCostUsd; }
        public void setMaxCostUsd(BigDecimal maxCostUsd) { this.maxCostUsd = maxCostUsd; }
    }
}
