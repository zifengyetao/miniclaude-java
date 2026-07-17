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

/**
 * 图静态校验端点的可变 HTTP 请求 DTO。
 *
 * <p>Bean Validation 负责 JSON 结构与基础数值约束，{@link GraphSpec} 和校验器负责节点
 * 唯一性、端点、可达性及循环规则。内部 DTO 只服务反序列化和领域转换。
 */
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

    /**
     * 将已完成 Bean Validation 的请求转换为不可变领域图。
     *
     * <p>前置条件是节点、边和限制对象均非空且内部字段有效；否则可能抛出空指针或领域参数
     * 异常。转换不修改请求且无外部副作用，字段不被并发修改时可重复调用。
     */
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

    /** 单个图节点的传输表示。 */
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

    /** 单条有向边的传输表示。 */
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

    /** 图执行上限的传输表示；更复杂的循环约束留给领域校验。 */
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
