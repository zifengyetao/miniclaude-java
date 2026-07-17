package com.miniclaude.domain.scenario;

import com.miniclaude.domain.graph.GraphSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** 一个可复现的场景包；所有依赖必须固定到精确版本。 */
public final class RolePack {
    public static final class VersionRef {
        private final String key;
        private final String version;

        public VersionRef(String key, String version) {
            this.key = text(key, "key");
            this.version = exact(version);
        }

        public String getKey() { return key; }
        public String getVersion() { return version; }
    }

    private final String id;
    private final String name;
    private final String version;
    private final VersionRef agentSpec;
    private final GraphSpec graph;
    private final List<VersionRef> rules;
    private final List<VersionRef> skills;
    private final List<VersionRef> verifiers;
    private final VersionRef evalSuite;

    public RolePack(String id, String name, String version, VersionRef agentSpec, GraphSpec graph,
                    List<VersionRef> rules, List<VersionRef> skills,
                    List<VersionRef> verifiers, VersionRef evalSuite) {
        this.id = text(id, "id");
        this.name = text(name, "name");
        this.version = exact(version);
        this.agentSpec = Objects.requireNonNull(agentSpec, "agentSpec");
        this.graph = Objects.requireNonNull(graph, "graph");
        this.rules = immutable(rules);
        this.skills = immutable(skills);
        this.verifiers = immutable(verifiers);
        this.evalSuite = Objects.requireNonNull(evalSuite, "evalSuite");
    }

    private static List<VersionRef> immutable(List<VersionRef> value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("version refs required");
        return Collections.unmodifiableList(new ArrayList<>(value));
    }

    private static String exact(String value) {
        String normalized = text(value, "version");
        if ("latest".equalsIgnoreCase(normalized) || normalized.contains("*")
                || normalized.contains("[") || normalized.contains("(")) {
            throw new IllegalArgumentException("exact version required");
        }
        return normalized;
    }

    private static String text(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " required");
        return value.trim();
    }

    public String getId() { return id; }
    public String getName() { return name; }
    public String getVersion() { return version; }
    public VersionRef getAgentSpec() { return agentSpec; }
    public GraphSpec getGraph() { return graph; }
    public List<VersionRef> getRules() { return rules; }
    public List<VersionRef> getSkills() { return skills; }
    public List<VersionRef> getVerifiers() { return verifiers; }
    public VersionRef getEvalSuite() { return evalSuite; }
}
