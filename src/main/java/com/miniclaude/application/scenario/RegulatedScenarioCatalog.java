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

/**
 * 受监管仿真场景目录。
 *
 * <p>Graph 将领域策略、PII 处理、只读证据、独立验证和四眼审批显式排成不可省略的链。
 * RolePack 同时声明 no-auto-action、simulation-only 和 evolution-max-l1：风控只输出
 * REVIEW/ESCALATE 建议，交易只生成 OMS 草稿，且演进上限 L1 不得改变这些安全边界。</p>
 */
@Service
public final class RegulatedScenarioCatalog {
    public static final String INVESTIGATION = "risk-investigation";
    public static final String TRADING = "trading-assistant";
    private final Map<String, RolePack> packs = new LinkedHashMap<>();

    public RegulatedScenarioCatalog() {
        register(INVESTIGATION, "风控调查 Agent", new String[][] {
                {"domain-policy", "POLICY"}, {"pii-mask", "POLICY"},
                {"rule-score", "TOOL"}, {"model-score", "TOOL"}, {"evidence", "RETRIEVAL"},
                {"case-package", "LLM"}, {"independent-verifier", "VERIFIER"},
                {"four-eyes", "APPROVAL"}, {"recommendation", "TERMINAL"}
        });
        register(TRADING, "交易辅助 Agent", new String[][] {
                {"domain-policy", "POLICY"}, {"market-read", "RETRIEVAL"},
                {"research-read", "RETRIEVAL"}, {"position-read", "RETRIEVAL"},
                {"proposal", "LLM"}, {"pre-trade-risk", "DETERMINISTIC"},
                {"four-eyes", "APPROVAL"}, {"oms-draft", "TERMINAL"}
        });
    }

    private void register(String id, String name, String[][] definitions) {
        List<GraphSpec.Node> nodes = new ArrayList<>();
        List<GraphSpec.Edge> edges = new ArrayList<>();
        for (int i = 0; i < definitions.length; i++) {
            nodes.add(new GraphSpec.Node(definitions[i][0],
                    GraphSpec.NodeType.valueOf(definitions[i][1]),
                    "regulated-simulation." + id + "." + definitions[i][0] + "@1.0.0"));
            // “verified”边表示只有上一步完成验证后才能进入下一节点，防止直接抵达终点制品。
            if (i > 0) edges.add(new GraphSpec.Edge(
                    definitions[i - 1][0], definitions[i][0], "verified"));
        }
        GraphSpec graph = new GraphSpec(id + "-regulated-graph", "1.0.0",
                definitions[0][0], nodes, edges,
                new GraphSpec.Limits(16, 1, new BigDecimal("1.00")));
        GraphValidationResult validation = new GraphValidator().validate(graph);
        if (!validation.isValid()) {
            // why：监管图缺边或节点无效时必须拒绝启动，不能退化成较少控制的流程。
            throw new IllegalStateException("invalid regulated graph: " + validation.getErrors());
        }
        RolePack pack = new RolePack(id, name, "1.0.0",
                ref(id + ".agent-spec"), graph,
                refs(id + ".regulated-domain-policy", id + ".no-auto-action",
                        id + ".four-eyes", id + ".evolution-max-l1"),
                refs(id + ".simulation-only"),
                refs(id + ".independent-verifier", id + ".deterministic-policy-verifier"),
                ref(id + ".regulated-eval-suite"));
        packs.put(id, pack);
    }

    /**
     * @param id 场景 ID：{@link #INVESTIGATION} 或 {@link #TRADING}
     * @return 不可变 RolePack
     * @throws IllegalArgumentException 未知场景
     */
    public RolePack get(String id) {
        RolePack pack = packs.get(id);
        if (pack == null) throw new IllegalArgumentException("regulated scenario not found: " + id);
        return pack;
    }

    /** @return 全部受监管 RolePack 的不可变列表 */
    public List<RolePack> list() {
        return Collections.unmodifiableList(new ArrayList<>(packs.values()));
    }

    private static RolePack.VersionRef ref(String key) {
        return new RolePack.VersionRef(key, "1.0.0");
    }

    private static List<RolePack.VersionRef> refs(String... keys) {
        List<RolePack.VersionRef> result = new ArrayList<>();
        Arrays.stream(keys).forEach(key -> result.add(ref(key)));
        return result;
    }
}
