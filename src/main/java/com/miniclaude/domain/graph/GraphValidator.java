package com.miniclaude.domain.graph;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** GraphSpec v1 的最小静态编译校验器。 */
public final class GraphValidator {

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

        if (spec.getNodes().containsKey(spec.getEntryNode())) {
            Set<String> reachable = reachableFrom(spec.getEntryNode(), adjacency);
            for (String nodeId : spec.getNodes().keySet()) {
                if (!reachable.contains(nodeId)) {
                    errors.add("unreachable node: " + nodeId);
                }
            }
        }

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
