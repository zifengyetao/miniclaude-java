package com.miniclaude.application.scenario;

import com.google.gson.Gson;
import com.miniclaude.domain.runtime.ToolRequest;
import com.miniclaude.domain.runtime.ToolRegistry;
import com.miniclaude.domain.runtime.ToolResult;
import com.miniclaude.domain.runtime.WorkspaceLease;
import com.miniclaude.domain.scenario.ScenarioPorts;
import com.miniclaude.domain.scenario.SqlGuard;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 把现有受控 ScenarioPorts 注册成共享 Harness Tools。
 *
 * <p>所有外部能力仍是 Fake/草稿：不写数据库、不发送 CRM、不应用 Patch、不 Push。</p>
 */
@Component
public class HarnessScenarioToolRegistrar {
    private static final Pattern PII = Pattern.compile(
            "(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)退款|投诉|法律|起诉|自杀|伤害|fraud|chargeback|legal|suicide");

    private final ToolRegistry gateway;
    private final ScenarioPorts.AnalyticsData analytics;
    private final ScenarioPorts.KnowledgeRetrieval knowledge;
    private final ScenarioPorts.CodingRepository coding;
    private final WorkspaceLease.Provider leases;
    private final BigDecimal approvalThreshold;
    private final int analyticsMaxRows;
    private final SqlGuard sqlGuard = new SqlGuard();
    private final Gson gson = new Gson();

    public HarnessScenarioToolRegistrar(
            ToolRegistry gateway,
            ScenarioPorts.AnalyticsData analytics,
            ScenarioPorts.KnowledgeRetrieval knowledge,
            ScenarioPorts.CodingRepository coding,
            WorkspaceLease.Provider leases,
            @Value("${platform.scenarios.analytics.approval-threshold-usd:5.00}")
            BigDecimal approvalThreshold,
            @Value("${platform.scenarios.analytics.max-rows:1000}") int analyticsMaxRows) {
        this.gateway = gateway;
        this.analytics = analytics;
        this.knowledge = knowledge;
        this.coding = coding;
        this.leases = leases;
        this.approvalThreshold = approvalThreshold;
        if (analyticsMaxRows < 1) throw new IllegalArgumentException("analyticsMaxRows must be positive");
        this.analyticsMaxRows = analyticsMaxRows;
        register();
    }

    private void register() {
        gateway.register("metric_lookup", request -> safe(() -> metric(request)));
        gateway.register("sql_guard", request -> safe(() -> guard(request)));
        gateway.register("estimate_query", request -> safe(() -> estimate(request)));
        gateway.register("execute_read_only", request -> safe(() -> query(request)));
        gateway.register("verify_citations", request -> safe(() -> verifyCitations(request)));
        gateway.register("emit_report_draft", request -> safe(() -> reportDraft(request)));

        gateway.register("mask_pii", request -> safe(() -> maskPii(request)));
        gateway.register("knowledge_search", request -> safe(() -> searchKnowledge(request)));
        gateway.register("check_compliance", request -> safe(() -> compliance(request)));
        gateway.register("draft_reply", request -> safe(() -> replyDraft(request)));
        gateway.register("request_human_handoff", request -> safe(() -> handoff(request)));

        gateway.register("repo_search", request -> safe(() -> explore(request)));
        gateway.register("read_file", request -> safe(() -> explore(request)));
        gateway.register("propose_patch", request -> safe(() -> proposePatch(request)));
        gateway.register("run_build", request -> safe(() -> verifyPatch(request)));
        gateway.register("run_tests", request -> safe(() -> verifyPatch(request)));
        gateway.register("review_patch", request -> safe(() -> reviewPatch(request)));
        gateway.register("emit_pr_draft", request -> safe(() -> prDraft(request)));
    }

    private ToolResult metric(ToolRequest request) {
        return success(map("definition", analytics.metricDefinition(required(request, "metric"))));
    }

    private ToolResult guard(ToolRequest request) {
        SqlGuard.GuardedSql guarded = guarded(request);
        return success(map("sql", guarded.getSql(), "limit", guarded.getLimit(), "readOnly", true));
    }

    private ToolResult estimate(ToolRequest request) {
        SqlGuard.GuardedSql guarded = guarded(request);
        ScenarioPorts.CostEstimate estimate = analytics.estimate(guarded.getSql());
        return success(map("scannedBytes", estimate.scannedBytes,
                "estimatedUsd", estimate.estimatedUsd,
                "approvalRequired", estimate.estimatedUsd.compareTo(approvalThreshold) > 0));
    }

    private ToolResult query(ToolRequest request) {
        SqlGuard.GuardedSql guarded = guarded(request);
        ScenarioPorts.CostEstimate estimate = analytics.estimate(guarded.getSql());
        if (estimate.estimatedUsd.compareTo(approvalThreshold) > 0) {
            return new ToolResult(false, gson.toJson(map(
                    "code", "ANALYTICS_APPROVAL_REQUIRED",
                    "estimatedUsd", estimate.estimatedUsd)));
        }
        ScenarioPorts.QueryResult result = analytics.executeReadOnly(
                guarded.getSql(), guarded.getLimit());
        return success(map("rows", result.rows, "citations", result.citations,
                "externalDbConnected", false));
    }

    private ToolResult verifyCitations(ToolRequest request) {
        List<?> citations = list(request, "citations");
        return citations.isEmpty()
                ? new ToolResult(false, "{\"code\":\"CITATIONS_REQUIRED\"}")
                : success(map("verified", true, "citationCount", citations.size()));
    }

    private ToolResult reportDraft(ToolRequest request) {
        return success(map("type", "REPORT_DRAFT", "content", request.getArguments(),
                "published", false));
    }

    private ToolResult maskPii(ToolRequest request) {
        String message = required(request, "message");
        String masked = PII.matcher(message).replaceAll("[PII]");
        return success(map("masked", masked, "piiDetected", !masked.equals(message)));
    }

    private ToolResult searchKnowledge(ToolRequest request) {
        String question = required(request, "question");
        if (PII.matcher(question).find()) {
            return new ToolResult(false, "{\"code\":\"RAW_PII_FORBIDDEN\"}");
        }
        return success(map("citations", knowledge.search(question)));
    }

    private ToolResult compliance(ToolRequest request) {
        String message = required(request, "message");
        boolean sensitive = SENSITIVE.matcher(message).find();
        return success(map("sensitive", sensitive, "autoSend", false));
    }

    private ToolResult replyDraft(ToolRequest request) {
        return success(map("type", "REPLY_DRAFT", "draft", required(request, "draft"),
                "sent", false, "externalCrmConnected", false));
    }

    private ToolResult handoff(ToolRequest request) {
        return success(map("type", "HUMAN_SUPPORT_HANDOFF", "required", true,
                "autoSend", false, "reason", required(request, "reason")));
    }

    private ToolResult explore(ToolRequest request) {
        validateCoding(request);
        Path workspace = workspace(request);
        try (WorkspaceLease lease = leases.acquire(workspace, request.getContext().getRunId())) {
            return new ToolResult(true, coding.exploreReadOnly(
                    lease.getWorkspace(), required(request, "goal")));
        }
    }

    private ToolResult proposePatch(ToolRequest request) {
        validateCoding(request);
        Path workspace = workspace(request);
        try (WorkspaceLease lease = leases.acquire(workspace, request.getContext().getRunId())) {
            return success(map("patch", coding.proposePatch(
                    lease.getWorkspace(), required(request, "goal")),
                    "proposalOnly", true));
        }
    }

    private ToolResult verifyPatch(ToolRequest request) {
        validateCoding(request);
        Path workspace = workspace(request);
        try (WorkspaceLease lease = leases.acquire(workspace, request.getContext().getRunId())) {
            ScenarioPorts.Verification result = coding.verify(
                    lease.getWorkspace(), required(request, "patch"));
            return new ToolResult(result.buildPassed && result.testsPassed, gson.toJson(map(
                    "buildPassed", result.buildPassed,
                    "testsPassed", result.testsPassed,
                    "output", result.output)));
        }
    }

    private ToolResult reviewPatch(ToolRequest request) {
        validateCoding(request);
        ScenarioPorts.Verification verification = new ScenarioPorts.Verification(
                bool(request, "buildPassed"), bool(request, "testsPassed"),
                String.valueOf(request.getArguments().getOrDefault("verificationOutput", "")));
        ScenarioPorts.Review review = coding.independentReview(
                required(request, "patch"), verification);
        return new ToolResult(review.approved, gson.toJson(map(
                "approved", review.approved, "summary", review.summary)));
    }

    private ToolResult prDraft(ToolRequest request) {
        validateCoding(request);
        return success(map("type", "PR_DRAFT", "title", required(request, "title"),
                "branch", required(request, "branch"), "externalPrCreated", false,
                "pushed", false));
    }

    private SqlGuard.GuardedSql guarded(ToolRequest request) {
        int requestedMaxRows = number(request, "maxRows", analyticsMaxRows);
        if (requestedMaxRows < 1 || requestedMaxRows > analyticsMaxRows) {
            throw new SecurityException("requested row limit exceeds platform cap");
        }
        return sqlGuard.validate(required(request, "sql"), requestedMaxRows);
    }

    private Path workspace(ToolRequest request) {
        // workspace 属于可信 ExecutionContext，模型参数不能选择其它租户/Run 的允许目录。
        return request.getContext().getWorkspace();
    }

    private void validateCoding(ToolRequest request) {
        String branch = String.valueOf(request.getArguments().getOrDefault("branch", ""));
        String combined = request.getArguments().values().toString().toLowerCase();
        if ("main".equalsIgnoreCase(branch) || "master".equalsIgnoreCase(branch)
                || combined.contains("--no-verify") || combined.contains("push --force")
                || combined.contains("push -f") || combined.contains("production deploy")
                || combined.contains("deploy production") || combined.contains(".env")
                || combined.contains("credentials") || combined.contains("private_key")) {
            throw new SecurityException("forbidden coding target");
        }
    }

    private ToolResult safe(ToolAction action) {
        try {
            return action.execute();
        } catch (RuntimeException rejected) {
            return new ToolResult(false, "{\"code\":\"TOOL_REJECTED\"}");
        }
    }

    private ToolResult success(Map<String, Object> value) {
        return new ToolResult(true, gson.toJson(value));
    }

    private static String required(ToolRequest request, String name) {
        Object value = request.getArguments().get(name);
        if (value == null || value.toString().trim().isEmpty()) {
            throw new IllegalArgumentException(name + " required");
        }
        return value.toString().trim();
    }

    private static int number(ToolRequest request, String name, int fallback) {
        Object value = request.getArguments().get(name);
        if (value == null) return fallback;
        return value instanceof Number
                ? ((Number) value).intValue() : Integer.parseInt(value.toString());
    }

    private static boolean bool(ToolRequest request, String name) {
        Object value = request.getArguments().get(name);
        return value instanceof Boolean ? (Boolean) value : Boolean.parseBoolean(String.valueOf(value));
    }

    private static List<?> list(ToolRequest request, String name) {
        Object value = request.getArguments().get(name);
        if (!(value instanceof List)) return Collections.emptyList();
        return (List<?>) value;
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return values;
    }

    @FunctionalInterface
    private interface ToolAction {
        ToolResult execute();
    }
}
