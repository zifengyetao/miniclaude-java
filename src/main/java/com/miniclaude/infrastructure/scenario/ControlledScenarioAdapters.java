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
 * 试点场景（Coding / 数据分析 / 智能客服）的<b>受控 Fake 适配器</b>。
 *
 * <p><b>设计原则</b>：
 * <ul>
 *   <li><b>确定性</b>：相同输入 → 相同输出，便于集成测试与 Harness 回放</li>
 *   <li><b>零外部副作用</b>：不执行 git、shell、真实 SQL、CRM 发送或 HTTP 外呼</li>
 *   <li><b>显式 Fake 标记</b>：返回值/引用 ID 含 fake 语义，防止与生产数据混淆</li>
 *   <li><b>「通过」仅验证编排</b>：{@link ScenarioPorts.Verification} 返回 build/test 通过
 *       只表示 Fake 链路可走通，<b>不代表</b>真实 CI 或数据库已校验</li>
 * </ul></p>
 *
 * <p><b>生产替换</b>：须注册独立 Bean 并重新走策略评审；禁止静默改本类逻辑冒充生产。</p>
 */
@Component
public class ControlledScenarioAdapters implements ScenarioPorts.CodingRepository,
        ScenarioPorts.AnalyticsData, ScenarioPorts.KnowledgeRetrieval {

    /**
     * 只读仓库探索：返回内存字符串快照，不读取磁盘 git 对象。
     *
     * @param workspace 工作区路径（仅用于构造 fake 标识）
     * @param goal      用户目标描述
     */
    @Override
    public String exploreReadOnly(Path workspace, String goal) {
        return "fake-readonly-snapshot:" + workspace.getFileName() + ":" + goal;
    }

    /**
     * 生成 unified diff 文本提案，<b>不写入</b> workspace 文件系统。
     *
     * <p>保持 Coding 场景「建议型自治」：Agent 产出可审查 diff，人工/审批后再应用。</p>
     */
    @Override
    public String proposePatch(Path workspace, String goal) {
        // 生成文本 diff 而不写入 workspace，保持 Coding 场景的“仅提案”边界。
        return "diff --git a/PROPOSAL.md b/PROPOSAL.md\n"
                + "--- /dev/null\n+++ b/PROPOSAL.md\n@@ -0,0 +1 @@\n+" + goal + "\n";
    }

    /**
     * Fake 构建/测试验证：补丁含 {@code FAKE_BUILD_FAILURE} 子串则失败。
     *
     * <p>用于测试编排对验证失败的分支，非真实 Maven/Gradle 执行。</p>
     */
    @Override
    public ScenarioPorts.Verification verify(Path workspace, String patch) {
        boolean pass = patch != null && !patch.contains("FAKE_BUILD_FAILURE");
        return new ScenarioPorts.Verification(pass, pass, pass ? "fake build/test passed" : "fake build failed");
    }

    /**
     * 独立审查员 Fake：需 build+test 通过且补丁不含 {@code REVIEW_REJECT}。
     *
     * <p>模拟 Coding 场景「双轨验证」——实现者与审查者分离，审查者 bean 可替换为真人审批。</p>
     */
    @Override
    public ScenarioPorts.Review independentReview(String patch, ScenarioPorts.Verification verification) {
        boolean approved = verification.buildPassed && verification.testsPassed
                && patch != null && !patch.contains("REVIEW_REJECT");
        return new ScenarioPorts.Review(approved, approved ? "independent fake reviewer approved"
                : "independent fake reviewer rejected");
    }

    /**
     * 查询成本估算 Fake：SQL 含 {@code expensive_table} 则触发「高成本」以测试审批分支。
     *
     * <p>行数/美元数为硬编码，不是真实 EXPLAIN 或计费模型。</p>
     */
    @Override
    public ScenarioPorts.CostEstimate estimate(String sql) {
        // 固定关键字只用于稳定触发审批测试，不是生产成本估算模型。
        boolean expensive = sql.toLowerCase().contains("expensive_table");
        return new ScenarioPorts.CostEstimate(expensive ? 50_000_000_000L : 10_000L,
                expensive ? new BigDecimal("12.50") : new BigDecimal("0.01"));
    }

    /**
     * 只读查询 Fake：不解析/执行 SQL，返回固定一行 metric=42。
     *
     * <p>{@code provenance} 含 SQL hash，便于测试引用链；{@code rowLimit} 当前被忽略。</p>
     */
    @Override
    public ScenarioPorts.QueryResult executeReadOnly(String sql, int rowLimit) {
        // 不解析或执行 SQL；只返回带 fake 来源引用的一行确定性数据。
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("metric", "fake-value");
        row.put("value", 42);
        return new ScenarioPorts.QueryResult(Collections.singletonList(row),
                Collections.singletonList("fake-query:" + Integer.toHexString(sql.hashCode())));
    }

    /** 返回 metric 定义 URI 占位符，不连接指标元数据服务 */
    @Override
    public String metricDefinition(String metric) {
        return "metric://" + metric + "@1.0.0";
    }

    /**
     * 知识检索 Fake：固定返回一条 kb-001 引用，不访问向量库/CRM。
     *
     * <p>客服场景据此生成「带引用」的回复草稿；禁止在本方法内发送消息给真实客户。</p>
     */
    @Override
    public List<ScenarioPorts.Citation> search(String question) {
        // 不访问 CRM/知识库网络，引用 ID 明确标记为 fake，防止混同生产证据。
        return Arrays.asList(new ScenarioPorts.Citation("kb-001", "受控知识条目",
                "这是 fake 知识库返回的可引用内容。"));
    }
}
