package com.miniclaude.infrastructure.scenario;

import com.miniclaude.domain.scenario.ScenarioPorts;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 确定性受控 fake。它不执行 git、shell、SQL、CRM 或网络调用；
 * 生产部署必须以独立 bean 显式替换对应 SPI。
 */
@Component
public class ControlledScenarioAdapters implements ScenarioPorts.CodingRepository,
        ScenarioPorts.AnalyticsData, ScenarioPorts.KnowledgeRetrieval {

    @Override
    public String exploreReadOnly(Path workspace, String goal) {
        return "fake-readonly-snapshot:" + workspace.getFileName() + ":" + goal;
    }

    @Override
    public String proposePatch(Path workspace, String goal) {
        return "diff --git a/PROPOSAL.md b/PROPOSAL.md\n"
                + "--- /dev/null\n+++ b/PROPOSAL.md\n@@ -0,0 +1 @@\n+" + goal + "\n";
    }

    @Override
    public ScenarioPorts.Verification verify(Path workspace, String patch) {
        boolean pass = patch != null && !patch.contains("FAKE_BUILD_FAILURE");
        return new ScenarioPorts.Verification(pass, pass, pass ? "fake build/test passed" : "fake build failed");
    }

    @Override
    public ScenarioPorts.Review independentReview(String patch, ScenarioPorts.Verification verification) {
        boolean approved = verification.buildPassed && verification.testsPassed
                && patch != null && !patch.contains("REVIEW_REJECT");
        return new ScenarioPorts.Review(approved, approved ? "independent fake reviewer approved"
                : "independent fake reviewer rejected");
    }

    @Override
    public ScenarioPorts.CostEstimate estimate(String sql) {
        boolean expensive = sql.toLowerCase().contains("expensive_table");
        return new ScenarioPorts.CostEstimate(expensive ? 50_000_000_000L : 10_000L,
                expensive ? new BigDecimal("12.50") : new BigDecimal("0.01"));
    }

    @Override
    public ScenarioPorts.QueryResult executeReadOnly(String sql, int rowLimit) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("metric", "fake-value");
        row.put("value", 42);
        return new ScenarioPorts.QueryResult(Collections.singletonList(row),
                Collections.singletonList("fake-query:" + Integer.toHexString(sql.hashCode())));
    }

    @Override
    public String metricDefinition(String metric) {
        return "metric://" + metric + "@1.0.0";
    }

    @Override
    public List<ScenarioPorts.Citation> search(String question) {
        return Arrays.asList(new ScenarioPorts.Citation("kb-001", "受控知识条目",
                "这是 fake 知识库返回的可引用内容。"));
    }
}
