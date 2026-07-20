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
 * 唯一性、端点、可达性及循环规则。内部嵌套 DTO 只服务反序列化和领域转换。
 */
public class ValidateGraphRequest {

    /** 图逻辑名称，用于诊断信息展示。 */
    @NotBlank
    private String name;
    /** 图定义版本号，写入 checkpoint 元数据。 */
    @NotBlank
    private String version;
    /** 入口节点 ID，须在 nodes 中存在。 */
    @NotBlank
    private String entryNode;
    /** 节点列表，至少一个。 */
    @Valid
    @NotEmpty
    private List<NodeRequest> nodes = new ArrayList<>();
    /** 有向边列表，可为空（单节点图）。 */
    @Valid
    private List<EdgeRequest> edges = new ArrayList<>();
    /** 执行资源上限，必填。 */
    @Valid
    @NotNull
    private LimitsRequest limits;

    /**
     * 将已完成 Bean Validation 的请求转换为不可变领域图。
     *
     * @return 不可变 {@link GraphSpec}
     * @throws NullPointerException 嵌套对象为 null 时（不应发生在 @Valid 通过后）
     * @throws IllegalArgumentException 领域构造参数非法
     * @implNote 转换不修改请求且无外部副作用；字段不被并发修改时可重复调用
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

    /** 单个图节点的 JSON 传输表示。 */
    public static class NodeRequest {
        /** 节点唯一 ID，在图内不可重复。 */
        @NotBlank
        private String id;
        /** 节点类型，决定运行时执行器选择。 */
        @NotNull
        private GraphSpec.NodeType type;
        /** 可选外部引用（资产坐标等）。 */
        private String reference;

        /** 转为不可变领域节点；包可见，仅供 {@link #toGraphSpec()} 调用。 */
        GraphSpec.Node toNode() { return new GraphSpec.Node(id, type, reference); }
        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public GraphSpec.NodeType getType() { return type; }
        public void setType(GraphSpec.NodeType type) { this.type = type; }
        public String getReference() { return reference; }
        public void setReference(String reference) { this.reference = reference; }
    }

    /** 单条有向边的 JSON 传输表示。 */
    public static class EdgeRequest {
        /** 源节点 ID。 */
        @NotBlank
        private String from;
        /** 目标节点 ID。 */
        @NotBlank
        private String to;
        /** 转移条件表达式；空或 {@code always} 表示无条件。 */
        private String condition;

        GraphSpec.Edge toEdge() { return new GraphSpec.Edge(from, to, condition); }
        public String getFrom() { return from; }
        public void setFrom(String from) { this.from = from; }
        public String getTo() { return to; }
        public void setTo(String to) { this.to = to; }
        public String getCondition() { return condition; }
        public void setCondition(String condition) { this.condition = condition; }
    }

    /** 图级执行上限的 JSON 传输表示。 */
    public static class LimitsRequest {
        /** 最大步骤数，必须为正。 */
        @Positive
        private int maxSteps;
        /** 最大图迭代次数（循环控制）。 */
        private int maxIterations;
        /** 最大累计成本（USD），必须为正。 */
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
