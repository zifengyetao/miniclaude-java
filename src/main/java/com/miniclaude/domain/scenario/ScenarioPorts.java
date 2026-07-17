package com.miniclaude.domain.scenario;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * 场景访问 Git、数据库和知识库的显式端口。
 *
 * <p>端口只声明场景需要的最小能力，默认实现是受控 fake，不会访问真实 Git、
 * 数据库、CRM 或网络。生产接入必须显式提供并审查独立适配器，不能把 fake 的
 * 测试结果误解为真实外部动作已经执行。</p>
 */
public final class ScenarioPorts {
    private ScenarioPorts() {}

    public interface CodingRepository {
        /** 只读探索工作区，不应产生文件、分支或远端副作用。 */
        String exploreReadOnly(Path workspace, String goal);
        /** 仅生成补丁提案；应用补丁和创建外部 PR 不属于该端口。 */
        String proposePatch(Path workspace, String goal);
        Verification verify(Path workspace, String patch);
        Review independentReview(String patch, Verification verification);
    }

    public interface AnalyticsData {
        /** 估算查询成本，但不执行查询。 */
        CostEstimate estimate(String sql);
        /** 仅接受已通过 SQL guard 的只读查询和显式行数上限。 */
        QueryResult executeReadOnly(String sql, int rowLimit);
        String metricDefinition(String metric);
    }

    public interface KnowledgeRetrieval {
        /** 返回可追溯引用；该能力不包含向 CRM 自动发送回复。 */
        List<Citation> search(String question);
    }

    public static final class Verification {
        public final boolean buildPassed;
        public final boolean testsPassed;
        public final String output;
        public Verification(boolean buildPassed, boolean testsPassed, String output) {
            this.buildPassed = buildPassed; this.testsPassed = testsPassed; this.output = output;
        }
    }

    public static final class Review {
        public final boolean approved;
        public final String summary;
        public Review(boolean approved, String summary) {
            this.approved = approved; this.summary = summary;
        }
    }

    public static final class CostEstimate {
        public final long scannedBytes;
        public final java.math.BigDecimal estimatedUsd;
        public CostEstimate(long scannedBytes, java.math.BigDecimal estimatedUsd) {
            this.scannedBytes = scannedBytes; this.estimatedUsd = estimatedUsd;
        }
    }

    public static final class QueryResult {
        public final List<Map<String, Object>> rows;
        public final List<String> citations;
        public QueryResult(List<Map<String, Object>> rows, List<String> citations) {
            this.rows = rows; this.citations = citations;
        }
    }

    public static final class Citation {
        public final String id;
        public final String title;
        public final String excerpt;
        public Citation(String id, String title, String excerpt) {
            this.id = id; this.title = title; this.excerpt = excerpt;
        }
    }
}
