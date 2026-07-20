package com.miniclaude.domain.scenario;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 试点场景（Coding / 数据分析 / 智能客服）的外部能力 Outbound Port 命名空间。
 * <p>
 * <b>为何放在 domain：</b>场景用例声明最小外部依赖，默认 Fake 实现，避免 application 直接依赖 Git/JDBC/CRM SDK。
 * <p>
 * <b>不变量：</b>端口<b>故意缺失</b>真实下单、发 CRM、写 Git 远端等能力；生产接入须独立审查的适配器。
 * <p>
 * <b>边界：</b>infrastructure {@code scenario} 包 Fake SPI；application {@code PilotScenarioService} 编排。
 */
public final class ScenarioPorts {
    private ScenarioPorts() {}

    /**
     * Coding 场景：Git 工作区只读探索 + 补丁提案（无 apply/PR）。
     */
    public interface CodingRepository {
        /** 只读探索；不产生 commit/push。 */
        String exploreReadOnly(Path workspace, String goal);
        /** 生成 patch 文本提案；应用补丁不属于本端口。 */
        String proposePatch(Path workspace, String goal);
        /** 对 patch 做构建/测试验证。 */
        Verification verify(Path workspace, String patch);
        /** 独立审查（模拟 CR）。 */
        Review independentReview(String patch, Verification verification);
    }

    /**
     * 数据分析场景：成本估算 + 只读 SQL（须先过 {@link SqlGuard}）。
     */
    public interface AnalyticsData {
        /** 估算扫描字节/费用，不执行 SQL。 */
        CostEstimate estimate(String sql);
        /** 执行已通过 guard 的 SELECT；rowLimit 为硬上限。 */
        QueryResult executeReadOnly(String sql, int rowLimit);
        /** 指标定义说明（文档/元数据）。 */
        String metricDefinition(String metric);
    }

    /**
     * 智能客服：知识检索 + 引用（无 CRM 自动发送）。
     */
    public interface KnowledgeRetrieval {
        /** 返回带 id 的可追溯引用列表。 */
        List<Citation> search(String question);
    }

    /** 构建/测试结果。 */
    public static final class Verification {
        /** 编译是否通过。 */
        public final boolean buildPassed;
        /** 测试是否通过。 */
        public final boolean testsPassed;
        /** 日志/输出摘要。 */
        public final String output;
        public Verification(boolean buildPassed, boolean testsPassed, String output) {
            this.buildPassed = buildPassed; this.testsPassed = testsPassed; this.output = output;
        }
    }

    /** 独立审查结论。 */
    public static final class Review {
        /** 是否批准合并/应用。 */
        public final boolean approved;
        /** 审查摘要。 */
        public final String summary;
        public Review(boolean approved, String summary) {
            this.approved = approved; this.summary = summary;
        }
    }

    /** SQL 成本估算。 */
    public static final class CostEstimate {
        /** 预估扫描字节数。 */
        public final long scannedBytes;
        /** 预估费用（美元）。 */
        public final java.math.BigDecimal estimatedUsd;
        public CostEstimate(long scannedBytes, java.math.BigDecimal estimatedUsd) {
            this.scannedBytes = scannedBytes; this.estimatedUsd = estimatedUsd;
        }
    }

    /** 只读查询结果。 */
    public static final class QueryResult {
        /** 结果行。 */
        public final List<Map<String, Object>> rows;
        /** 数据来源引用。 */
        public final List<String> citations;
        public QueryResult(List<Map<String, Object>> rows, List<String> citations) {
            this.rows = rows; this.citations = citations;
        }
    }

    /** 知识库引用条目。 */
    public static final class Citation {
        public final String id;
        public final String title;
        public final String excerpt;
        public Citation(String id, String title, String excerpt) {
            this.id = id; this.title = title; this.excerpt = excerpt;
        }
    }
}
