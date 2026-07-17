package com.miniclaude.domain.scenario;

import com.miniclaude.domain.graph.GraphSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 一个可复现、可审计的场景角色包。
 *
 * <p>RolePack 描述“由谁、按什么流程、受哪些约束执行”：agentSpec 定义角色，
 * GraphSpec 定义节点及流转顺序，rules/skills/verifiers/evalSuite 则固定安全规则、
 * 能力、验证器和评测集。所有引用都要求精确版本，避免同一次场景在依赖漂移后
 * 得到不可复现的行为。</p>
 */
public final class RolePack {
    /** 指向一个不可漂移的版本化依赖；禁止 latest、通配符和版本区间。 */
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
        // why：场景包缺少任一类约束都不应退化为“无约束执行”，因此空引用直接失败。
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("version refs required");
        return Collections.unmodifiableList(new ArrayList<>(value));
    }

    private static String exact(String value) {
        String normalized = text(value, "version");
        // why：动态版本会让规则、验证器或评测集静默变化，破坏审计和结果复现。
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
