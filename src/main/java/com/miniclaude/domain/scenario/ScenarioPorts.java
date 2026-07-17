package com.miniclaude.domain.scenario;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/** 外部 Git、数据库和 CRM/知识库的显式 SPI；生产接入必须另行提供适配器。 */
public final class ScenarioPorts {
    private ScenarioPorts() {}

    public interface CodingRepository {
        String exploreReadOnly(Path workspace, String goal);
        String proposePatch(Path workspace, String goal);
        Verification verify(Path workspace, String patch);
        Review independentReview(String patch, Verification verification);
    }

    public interface AnalyticsData {
        CostEstimate estimate(String sql);
        QueryResult executeReadOnly(String sql, int rowLimit);
        String metricDefinition(String metric);
    }

    public interface KnowledgeRetrieval {
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
