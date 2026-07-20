package com.miniclaude.application.scenario;

import com.miniclaude.domain.graph.GraphSpec;
import com.miniclaude.domain.graph.GraphValidationResult;
import com.miniclaude.domain.graph.GraphValidator;
import com.miniclaude.domain.scenario.RolePack;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
/**
 * 内置试点场景的 RolePack 目录。
 *
 * <p>每个 RolePack 把角色规范、线性 Graph、规则、技能、验证器和评测集固定在 1.0.0。
 * Graph 节点类型表达信任边界：POLICY 先阻断，TOOL/RETRIEVAL 调用受控端口，
 * VERIFIER 独立校验，APPROVAL/HUMAN_TASK 暂停等待人工，TERMINAL 只产出制品。</p>
 */
public class ScenarioCatalog {
    public static final String CODING = "coding-agent";
    public static final String ANALYST = "data-analyst";
    public static final String SUPPORT = "customer-support";
    private final Map<String, RolePack> packs = new LinkedHashMap<>();

    public ScenarioCatalog() {
        register(CODING, "Coding Agent", new String[][]{
                {"explore", "RETRIEVAL"}, {"plan", "PLANNER"}, {"lease", "POLICY"},
                {"patch", "TOOL"}, {"verify", "VERIFIER"}, {"review", "VERIFIER"},
                {"pr-draft", "TERMINAL"}});
        registerAnalyst();
        register(SUPPORT, "智能客服", new String[][]{
                {"pii-mask", "POLICY"}, {"retrieve", "RETRIEVAL"}, {"compliance", "POLICY"},
                {"draft", "LLM"}, {"confidence", "VERIFIER"}, {"handoff", "HUMAN_TASK"},
                {"draft-artifact", "TERMINAL"}});
    }

    private void register(String id, String name, String[][] definitions) {
        List<GraphSpec.Node> nodes = new ArrayList<>();
        List<GraphSpec.Edge> edges = new ArrayList<>();
        for (int i = 0; i < definitions.length; i++) {
            nodes.add(new GraphSpec.Node(definitions[i][0],
                    GraphSpec.NodeType.valueOf(definitions[i][1]), id + "." + definitions[i][0] + "@1.0.0"));
            // 内置场景按定义顺序构造成单向图，避免执行器跳过中间安全节点。
            if (i > 0) edges.add(new GraphSpec.Edge(definitions[i - 1][0], definitions[i][0], "always"));
        }
        GraphSpec graph = new GraphSpec(id + "-graph", "1.0.0", definitions[0][0], nodes, edges,
                new GraphSpec.Limits(20, 1, new BigDecimal("5.00")));
        register(id, name, graph);
    }

    /**
     * 分析场景包含真实条件分支：低成本从 estimate 直接进入 query，高成本先进入 approval。
     * 条件由 GraphRunner 对持久状态字段解释，服务层不得再用 if/else 决定下一节点。
     */
    private void registerAnalyst() {
        List<GraphSpec.Node> nodes = Arrays.asList(
                new GraphSpec.Node("sql-guard", GraphSpec.NodeType.POLICY, ANALYST + ".sql-guard@1.0.0"),
                new GraphSpec.Node("metric", GraphSpec.NodeType.RETRIEVAL, ANALYST + ".metric@1.0.0"),
                new GraphSpec.Node("estimate", GraphSpec.NodeType.DETERMINISTIC, ANALYST + ".estimate@1.0.0"),
                new GraphSpec.Node("approval", GraphSpec.NodeType.APPROVAL, ANALYST + ".approval@1.0.0"),
                new GraphSpec.Node("query", GraphSpec.NodeType.TOOL, ANALYST + ".query@1.0.0"),
                new GraphSpec.Node("verify", GraphSpec.NodeType.VERIFIER, ANALYST + ".verify@1.0.0"),
                new GraphSpec.Node("report", GraphSpec.NodeType.TERMINAL, ANALYST + ".report@1.0.0"));
        List<GraphSpec.Edge> edges = Arrays.asList(
                new GraphSpec.Edge("sql-guard", "metric", "always"),
                new GraphSpec.Edge("metric", "estimate", "always"),
                new GraphSpec.Edge("estimate", "approval", "approvalRequired=true"),
                new GraphSpec.Edge("estimate", "query", "approvalRequired=false"),
                new GraphSpec.Edge("approval", "query", "always"),
                new GraphSpec.Edge("query", "verify", "always"),
                new GraphSpec.Edge("verify", "report", "always"));
        register(ANALYST, "数据分析师", new GraphSpec(ANALYST + "-graph", "1.1.0",
                "sql-guard", nodes, edges, new GraphSpec.Limits(20, 1, new BigDecimal("5.00"))));
    }

    private void register(String id, String name, GraphSpec graph) {
        GraphValidationResult result = new GraphValidator().validate(graph);
        // why：错误的内置图属于部署配置错误，应在目录构造时失败，而不是运行中降级。
        if (!result.isValid()) throw new IllegalStateException("invalid built-in graph: " + result.getErrors());
        RolePack pack = new RolePack(id, name, graph.getVersion(), ref(id + ".agent-spec"),
                graph, refs(id + ".safety", id + ".compliance"),
                refs(id + ".workflow"), refs(id + ".output", id + ".safety"),
                ref(id + ".eval-suite"));
        packs.put(id, pack);
    }

    private static RolePack.VersionRef ref(String key) {
        return new RolePack.VersionRef(key, "1.0.0");
    }

    private static List<RolePack.VersionRef> refs(String... keys) {
        List<RolePack.VersionRef> refs = new ArrayList<>();
        Arrays.stream(keys).forEach(key -> refs.add(ref(key)));
        return refs;
    }

    /** @return 全部内置试点 RolePack */
    public List<RolePack> list() { return Collections.unmodifiableList(new ArrayList<>(packs.values())); }

    /**
     * @param id 场景 ID
     * @throws IllegalArgumentException 模板不存在
     */
    public RolePack get(String id) {
        RolePack pack = packs.get(id);
        if (pack == null) throw new IllegalArgumentException("scenario template not found: " + id);
        return pack;
    }
}
