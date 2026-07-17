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
            if (i > 0) edges.add(new GraphSpec.Edge(
                    definitions[i - 1][0], definitions[i][0], "verified"));
        }
        GraphSpec graph = new GraphSpec(id + "-regulated-graph", "1.0.0",
                definitions[0][0], nodes, edges,
                new GraphSpec.Limits(16, 1, new BigDecimal("1.00")));
        GraphValidationResult validation = new GraphValidator().validate(graph);
        if (!validation.isValid()) {
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

    public RolePack get(String id) {
        RolePack pack = packs.get(id);
        if (pack == null) throw new IllegalArgumentException("regulated scenario not found: " + id);
        return pack;
    }

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
