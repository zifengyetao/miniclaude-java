package com.miniclaude.domain.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@link GraphSpec} v1 的纯内存静态校验器（无 I/O、无 Spring 依赖）。
 * <p>
 * <b>为何放在 domain：</b>图可执行性规则属于编排领域语言，应在发布/注册前于 domain 层验证。
 * <p>
 * <b>不变量：</b>无实例状态，线程安全；{@code spec==null} 抛 NPE；同一 spec 重复校验结果一致（幂等）。
 * <p>
 * <b>边界：</b>application GraphController 调用；不解析 {@code Edge.condition} 表达式语义。
 */
public final class GraphValidator {

    /**
     * 汇总图定义中可一次性发现的错误和告警。
     * <p>
     * <b>校验规则（错误 → 图不可执行）：</b>
     * <ul>
     *   <li>nodes 为空</li>
     *   <li>entryNode 不存在于 nodes</li>
     *   <li>limits.maxSteps &lt; 1</li>
     *   <li>limits.maxCostUsd 非 null 且 ≤ 0</li>
     *   <li>边端点不存在</li>
     *   <li>从 entry 不可达的节点</li>
     *   <li>存在环且 maxIterations &lt; 1</li>
     *   <li>无 TERMINAL 类型节点</li>
     * </ul>
     * <b>告警（可执行但可疑）：</b>无环图 maxIterations &gt; 1。
     *
     * @param spec 已通过 GraphSpec 构造器基础校验的图定义
     * @return 不可变 {@link GraphValidationResult}
     */
    public GraphValidationResult validate(GraphSpec spec) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        // --- 结构前置：空图直接失败，无需后续可达性分析 ---
        if (spec.getNodes().isEmpty()) {
            errors.add("graph must contain at least one node");
            return new GraphValidationResult(errors, warnings);
        }
        if (!spec.getNodes().containsKey(spec.getEntryNode())) {
            errors.add("entry node does not exist: " + spec.getEntryNode());
        }
        if (spec.getLimits().getMaxSteps() < 1) {
            errors.add("maxSteps must be positive");
        }
        if (spec.getLimits().getMaxCostUsd() != null
                && spec.getLimits().getMaxCostUsd().signum() <= 0) {
            errors.add("maxCostUsd must be positive");
        }

        // --- 邻接表：仅收录端点合法的边，避免脏边污染环检测与可达性 ---
        Map<String, List<String>> adjacency = new HashMap<>();
        for (String nodeId : spec.getNodes().keySet()) {
            adjacency.put(nodeId, new ArrayList<>());
        }
        for (GraphSpec.Edge edge : spec.getEdges()) {
            if (!spec.getNodes().containsKey(edge.getFrom())) {
                errors.add("edge source does not exist: " + edge.getFrom());
                continue;
            }
            if (!spec.getNodes().containsKey(edge.getTo())) {
                errors.add("edge target does not exist: " + edge.getTo());
                continue;
            }
            adjacency.get(edge.getFrom()).add(edge.getTo());
        }

        // --- 可达性：entry 无效时跳过（entry 错误已足够说明不可执行）---
        if (spec.getNodes().containsKey(spec.getEntryNode())) {
            Set<String> reachable = reachableFrom(spec.getEntryNode(), adjacency);
            for (String nodeId : spec.getNodes().keySet()) {
                if (!reachable.contains(nodeId)) {
                    errors.add("unreachable node: " + nodeId);
                }
            }
        }

        // --- 环与迭代上限：有环必须 maxIterations≥1，否则运行时可能死循环 ---
        boolean hasCycle = hasCycle(adjacency);
        if (hasCycle && spec.getLimits().getMaxIterations() < 1) {
            errors.add("cyclic graph requires a positive maxIterations limit");
        }
        if (!hasCycle && spec.getLimits().getMaxIterations() > 1) {
            warnings.add("maxIterations is greater than one for an acyclic graph");
        }

        // --- 终止性：至少一个 TERMINAL 节点，否则 Run 无明确结束态 ---
        boolean hasTerminal = spec.getNodes().values().stream()
                .anyMatch(node -> node.getType() == GraphSpec.NodeType.TERMINAL);
        if (!hasTerminal) {
            errors.add("graph must contain a terminal node");
        }

        return new GraphValidationResult(errors, warnings);
    }

    /**
     * BFS 从 entry 出发求可达节点集合。
     *
     * @param entry     入口节点 ID
     * @param adjacency 邻接表（仅合法边）
     */
    private Set<String> reachableFrom(String entry, Map<String, List<String>> adjacency) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(entry);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!visited.add(current)) {
                continue; // 已访问，跳过避免 BFS 重复入队
            }
            queue.addAll(adjacency.getOrDefault(current, java.util.Collections.emptyList()));
        }
        return visited;
    }

    /** 对邻接表每个起点做 DFS，检测是否存在回边（环）。 */
    private boolean hasCycle(Map<String, List<String>> adjacency) {
        Set<String> visited = new HashSet<>();
        Set<String> active = new HashSet<>();
        for (String node : adjacency.keySet()) {
            if (hasCycle(node, adjacency, visited, active)) {
                return true;
            }
        }
        return false;
    }

    /**
     * DFS 环检测：active 为当前递归栈；重新进入栈中节点即发现回边。
     */
    private boolean hasCycle(
            String node,
            Map<String, List<String>> adjacency,
            Set<String> visited,
            Set<String> active) {
        if (active.contains(node)) {
            return true; // 回边：node 仍在当前 DFS 路径上
        }
        if (!visited.add(node)) {
            return false; // 已完全处理过的子树，无新环
        }
        active.add(node);
        for (String next : adjacency.getOrDefault(node, java.util.Collections.emptyList())) {
            if (hasCycle(next, adjacency, visited, active)) {
                return true;
            }
        }
        active.remove(node); // 回溯：离开当前路径
        return false;
    }
}
