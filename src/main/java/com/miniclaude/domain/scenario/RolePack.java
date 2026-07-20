package com.miniclaude.domain.scenario;

import com.miniclaude.domain.graph.GraphSpec;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * 可复现、可审计的场景角色包（Scenario 编排单元）。
 * <p>
 * <b>为何放在 domain：</b>绑定 agentSpec、Graph、规则/技能/验证器/评测集的<b>精确版本</b>，
 * 是场景 Catalog 的核心领域模型。
 * <p>
 * <b>不变量：</b>
 * <ul>
 *   <li>所有 VersionRef 禁止 latest/通配符/版本区间。</li>
 *   <li>rules/skills/verifiers 列表非空；evalSuite 非 null。</li>
 *   <li>graph 非 null，且应通过 {@link com.miniclaude.domain.graph.GraphValidator}。</li>
 * </ul>
 * <p>
 * <b>边界：</b>application {@code ScenarioCatalog} 静态定义；infrastructure 不修改 RolePack 语义。
 */
public final class RolePack {

    /**
     * 指向不可漂移的版本化依赖。
     * <p>格式约定：key + 精确 version，由 Registry 解析为 {@link com.miniclaude.domain.governance.VersionedAsset}。
     */
    public static final class VersionRef {
        /** 资产/定义键。 */
        private final String key;
        /** 精确版本号（非 latest）。 */
        private final String version;

        public VersionRef(String key, String version) {
            this.key = text(key, "key");
            this.version = exact(version);
        }

        /** @return 键 */
        public String getKey() { return key; }
        /** @return 精确版本 */
        public String getVersion() { return version; }
    }

    /** RolePack 唯一 ID。 */
    private final String id;
    /** 展示名称。 */
    private final String name;
    /** RolePack 自身版本。 */
    private final String version;
    /** 绑定的 Agent 规格引用。 */
    private final VersionRef agentSpec;
    /** 场景执行图（节点/边/limits）。 */
    private final GraphSpec graph;
    /** 策略规则资产引用列表（非空）。 */
    private final List<VersionRef> rules;
    /** 技能资产引用列表（非空）。 */
    private final List<VersionRef> skills;
    /** 验证器资产引用列表（非空）。 */
    private final List<VersionRef> verifiers;
    /** 评测集引用（进化/发布 gate）。 */
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

    /** 复制并冻结 VersionRef 列表；空列表拒绝（场景不得无约束执行）。 */
    private static List<VersionRef> immutable(List<VersionRef> value) {
        if (value == null || value.isEmpty()) throw new IllegalArgumentException("version refs required");
        return Collections.unmodifiableList(new ArrayList<>(value));
    }

    /** 拒绝动态版本，保证审计可复现。 */
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

    /** @return ID */
    public String getId() { return id; }
    /** @return 名称 */
    public String getName() { return name; }
    /** @return 版本 */
    public String getVersion() { return version; }
    /** @return Agent 规格引用 */
    public VersionRef getAgentSpec() { return agentSpec; }
    /** @return 图定义 */
    public GraphSpec getGraph() { return graph; }
    /** @return 规则引用列表 */
    public List<VersionRef> getRules() { return rules; }
    /** @return 技能引用列表 */
    public List<VersionRef> getSkills() { return skills; }
    /** @return 验证器引用列表 */
    public List<VersionRef> getVerifiers() { return verifiers; }
    /** @return 评测集引用 */
    public VersionRef getEvalSuite() { return evalSuite; }
}
