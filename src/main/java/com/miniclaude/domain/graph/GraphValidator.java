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
 * {@link GraphSpec} v1 的纯内存静态校验器。
 *
 * <p>本类只检查图结构、可达性以及循环与执行上限之间的约束，不解析节点引用、
 * 条件表达式，也不执行任何节点。实例不保存状态，可安全地被多个请求并发复用。
 */
public final class GraphValidator {

    /**
     * 汇总图定义中可一次性发现的错误和告警。
     *
     * <p>前置条件：{@code spec} 及其聚合内部元素已经通过构造器的基础校验。
     * 结构问题以结果中的错误返回，而不是在发现首个问题时抛出；传入 {@code null}
     * 则会自然抛出 {@link NullPointerException}。该方法无副作用，重复校验同一对象
     * 具有幂等性，并可并发调用。
     */
    public GraphValidationResult validate(GraphSpec spec) {
        List<String> errors = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

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

        // 先构造只含合法端点的邻接表，避免无效边污染后续可达性和环检测结论。
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

        // 仅在入口真实存在时计算可达性，否则入口错误已经足以说明图不可执行。
        if (spec.getNodes().containsKey(spec.getEntryNode())) {
            Set<String> reachable = reachableFrom(spec.getEntryNode(), adjacency);
            for (String nodeId : spec.getNodes().keySet()) {
                if (!reachable.contains(nodeId)) {
                    errors.add("unreachable node: " + nodeId);
                }
            }
        }

        // 环本身允许存在，但必须有显式迭代上限，防止运行时出现无界循环。
        boolean hasCycle = hasCycle(adjacency);
        if (hasCycle && spec.getLimits().getMaxIterations() < 1) {
            errors.add("cyclic graph requires a positive maxIterations limit");
        }
        if (!hasCycle && spec.getLimits().getMaxIterations() > 1) {
            warnings.add("maxIterations is greater than one for an acyclic graph");
        }

        boolean hasTerminal = spec.getNodes().values().stream()
                .anyMatch(node -> node.getType() == GraphSpec.NodeType.TERMINAL);
        if (!hasTerminal) {
            errors.add("graph must contain a terminal node");
        }

        return new GraphValidationResult(errors, warnings);
    }

    private Set<String> reachableFrom(String entry, Map<String, List<String>> adjacency) {
        Set<String> visited = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(entry);
        while (!queue.isEmpty()) {
            String current = queue.removeFirst();
            if (!visited.add(current)) {
                continue;
            }
            queue.addAll(adjacency.getOrDefault(current, java.util.Collections.emptyList()));
        }
        return visited;
    }

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

    private boolean hasCycle(
            String node,
            Map<String, List<String>> adjacency,
            Set<String> visited,
            Set<String> active) {
        // active 表示当前 DFS 调用栈；重新进入调用栈中的节点才是回边。
        if (active.contains(node)) {
            return true;
        }
        if (!visited.add(node)) {
            return false;
        }
        active.add(node);
        for (String next : adjacency.getOrDefault(node, java.util.Collections.emptyList())) {
            if (hasCycle(next, adjacency, visited, active)) {
                return true;
            }
        }
        active.remove(node);
        return false;
    }
}
