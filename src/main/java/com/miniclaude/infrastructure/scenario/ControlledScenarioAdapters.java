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
 * 试点场景的确定性受控 fake 适配器。
 *
 * <p>它只根据输入在内存中构造快照、补丁、查询行和知识引用，不执行 git、shell、SQL、
 * CRM 或网络调用，也不创建 PR 或发送客服消息。这里返回“通过”仅用于验证编排和安全边界，
 * 不代表真实构建、数据库或知识库已验证；生产接入必须以独立 bean 显式替换并重新评审权限。</p>
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
        // 生成文本 diff 而不写入 workspace，保持 Coding 场景的“仅提案”边界。
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
        // 固定关键字只用于稳定触发审批测试，不是生产成本估算模型。
        boolean expensive = sql.toLowerCase().contains("expensive_table");
        return new ScenarioPorts.CostEstimate(expensive ? 50_000_000_000L : 10_000L,
                expensive ? new BigDecimal("12.50") : new BigDecimal("0.01"));
    }

    @Override
    public ScenarioPorts.QueryResult executeReadOnly(String sql, int rowLimit) {
        // 不解析或执行 SQL；只返回带 fake 来源引用的一行确定性数据。
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
        // 不访问 CRM/知识库网络，引用 ID 明确标记为 fake，防止混同生产证据。
        return Arrays.asList(new ScenarioPorts.Citation("kb-001", "受控知识条目",
                "这是 fake 知识库返回的可引用内容。"));
    }
}
