package com.miniclaude.domain.graph;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 供应商无关且不可变的 Agent 图编排定义（Graph DSL 领域模型）。
 * <p>
 * <b>为何放在 domain：</b>节点类型、边、入口与执行上限是场景/数字员工编排的核心语义，
 * 不应依赖 Temporal、Spring 或具体 LLM SDK。
 * <p>
 * <b>不变量：</b>
 * <ul>
 *   <li>节点 ID 唯一；{@code name}/{@code version}/{@code entryNode} 非空白。</li>
 *   <li>{@code limits} 非 null；静态结构校验由 {@link GraphValidator} 负责。</li>
 *   <li>构造时复制 nodes/edges，外部修改集合不影响本对象。</li>
 * </ul>
 * <p>
 * <b>边界：</b>
 * <ul>
 *   <li><b>application</b>：GraphController 校验/查询；RolePack 嵌入 graph。</li>
 *   <li><b>infrastructure</b>：编排器按 entryNode 与边条件步进（当前多为线性对齐）。</li>
 * </ul>
 */
public final class GraphSpec {

    /**
     * 节点的抽象能力类型（不绑定具体模型、工具或工作流引擎）。
     * <p>运行时由 {@code reference} 字段指向 Registry 中具体资产 key@version。
     */
    public enum NodeType {
        /** 确定性逻辑节点（无 LLM，纯代码/规则）。 */
        DETERMINISTIC,
        /** 大模型推理节点。 */
        LLM,
        /** 规划器：分解目标为子步骤。 */
        PLANNER,
        /** 执行器：执行计划中的单步。 */
        EXECUTOR,
        /** 工具调用节点（经 {@link com.miniclaude.domain.runtime.ToolGateway}）。 */
        TOOL,
        /** 检索/RAG 节点。 */
        RETRIEVAL,
        /** 策略判定节点（经 {@link com.miniclaude.domain.runtime.PolicyEngine}）。 */
        POLICY,
        /** 人工审批节点（转入 {@link com.miniclaude.domain.durable.ApprovalRequest}）。 */
        APPROVAL,
        /** 结果验证节点（Verifier 资产）。 */
        VERIFIER,
        /** 嵌套子图（引用另一 GraphSpec）。 */
        SUBGRAPH,
        /** 等待外部事件（resume 条件由边 condition 表达）。 */
        WAIT_EVENT,
        /** 人工任务（HITL，非自动审批）。 */
        HUMAN_TASK,
        /** 终止节点：到达后 Run 可进入 VERIFYING/SUCCEEDED 路径。 */
        TERMINAL
    }

    /** 图中的不可变节点。 */
    public static final class Node {
        /** 节点唯一 ID，与边 from/to 引用一致。 */
        private final String id;
        /** 节点能力类型，决定编排器分支行为。 */
        private final NodeType type;
        /** 外部注册表引用（如 prompt key、tool name、subgraph name）；空串表示无引用。 */
        private final String reference;

        /**
         * @param id        节点 ID，非空白
         * @param type      节点类型，非 null
         * @param reference 引用键，null 归一化为 ""
         */
        public Node(String id, NodeType type, String reference) {
            this.id = requireText(id, "node.id");
            this.type = Objects.requireNonNull(type, "node.type");
            this.reference = reference == null ? "" : reference.trim();
        }

        /** @return 节点 ID */
        public String getId() { return id; }
        /** @return 节点类型 */
        public NodeType getType() { return type; }
        /** @return 外部引用键 */
        public String getReference() { return reference; }
    }

    /**
     * 两个节点之间的有向边。
     * <p>
     * {@code condition} 在本领域对象中为<b>不透明字符串</b>，由 application/infrastructure 解释
     * （如 {@code "approved"}、{@code "deny"}、SpEL/CEL 表达式）；GraphValidator 不解析条件语义。
     */
    public static final class Edge {
        /** 源节点 ID。 */
        private final String from;
        /** 目标节点 ID。 */
        private final String to;
        /** 转移条件；空串表示无条件（默认出边）。 */
        private final String condition;

        public Edge(String from, String to, String condition) {
            this.from = requireText(from, "edge.from");
            this.to = requireText(to, "edge.to");
            this.condition = condition == null ? "" : condition.trim();
        }

        /** @return 源节点 */
        public String getFrom() { return from; }
        /** @return 目标节点 */
        public String getTo() { return to; }
        /** @return 条件表达式（不透明） */
        public String getCondition() { return condition; }
    }

    /**
     * 执行器必须遵守的全局上限（与 {@link com.miniclaude.domain.platform.AgentRun} 的步数/费用协同）。
     */
    public static final class Limits {
        /** 图内最大步数（≥1，见 GraphValidator）。 */
        private final int maxSteps;
        /**
         * 有环图的最大迭代次数；无环图 &gt;1 时 Validator 告警。
         * 有环且 &lt;1 时报错（防止无界循环）。
         */
        private final int maxIterations;
        /** 可选费用上限（美元）；null 表示不限制。 */
        private final BigDecimal maxCostUsd;

        public Limits(int maxSteps, int maxIterations, BigDecimal maxCostUsd) {
            this.maxSteps = maxSteps;
            this.maxIterations = maxIterations;
            this.maxCostUsd = maxCostUsd;
        }

        /** @return 最大步数 */
        public int getMaxSteps() { return maxSteps; }
        /** @return 最大迭代次数（环检测相关） */
        public int getMaxIterations() { return maxIterations; }
        /** @return 费用上限 */
        public BigDecimal getMaxCostUsd() { return maxCostUsd; }
    }

    /** 图名称（Catalog 内唯一性由 application 保证）。 */
    private final String name;
    /** 图语义版本（与 RolePack/Governance 资产版本对齐）。 */
    private final String version;
    /** 执行起始节点 ID，必须存在于 nodes 中。 */
    private final String entryNode;
    /** 节点 ID → Node 不可变映射（LinkedHashMap 保持插入顺序）。 */
    private final Map<String, Node> nodes;
    /** 有向边列表（不可变）。 */
    private final List<Edge> edges;
    /** 执行上限。 */
    private final Limits limits;

    /**
     * 创建图定义并对节点建立索引。
     *
     * @throws IllegalArgumentException 重复节点 ID、必填字段空白
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
        // 建立节点索引，重复 ID 立即失败
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

    /** @return 图名称 */
    public String getName() { return name; }
    /** @return 图版本 */
    public String getVersion() { return version; }
    /** @return 入口节点 ID */
    public String getEntryNode() { return entryNode; }
    /** @return 不可变节点映射 */
    public Map<String, Node> getNodes() { return nodes; }
    /** @return 不可变边列表 */
    public List<Edge> getEdges() { return edges; }
    /** @return 执行上限 */
    public Limits getLimits() { return limits; }
}
