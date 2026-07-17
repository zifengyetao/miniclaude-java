package com.miniclaude.application.scenario;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.miniclaude.application.governance.AuditService;
import com.miniclaude.application.platform.AgentPlatformService;
import com.miniclaude.domain.durable.ApprovalRequest;
import com.miniclaude.domain.durable.DurableOrchestrator;
import com.miniclaude.domain.durable.DurableStores;
import com.miniclaude.domain.graph.GraphValidationResult;
import com.miniclaude.domain.graph.GraphValidator;
import com.miniclaude.domain.platform.AgentDefinition;
import com.miniclaude.domain.platform.AgentRun;
import com.miniclaude.domain.platform.ExecutionMode;
import com.miniclaude.domain.runtime.WorkspaceLease;
import com.miniclaude.domain.scenario.RolePack;
import com.miniclaude.domain.scenario.ScenarioArtifact;
import com.miniclaude.domain.scenario.ScenarioPorts;
import com.miniclaude.domain.scenario.SqlGuard;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
public class PilotScenarioService {
    private static final Pattern PII = Pattern.compile(
            "(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)退款|投诉|法律|起诉|自杀|伤害|fraud|chargeback|legal|suicide");
    private final ScenarioCatalog catalog;
    private final AgentPlatformService platform;
    private final DurableOrchestrator orchestrator;
    private final DurableStores.ApprovalService approvals;
    private final WorkspaceLease.Provider leases;
    private final ScenarioPorts.CodingRepository coding;
    private final ScenarioPorts.AnalyticsData analytics;
    private final ScenarioPorts.KnowledgeRetrieval knowledge;
    private final ScenarioArtifact.Repository artifacts;
    private final AuditService audit;
    private final MeterRegistry meters;
    private final BigDecimal approvalThreshold;
    private final SqlGuard sqlGuard = new SqlGuard();
    private final GraphValidator graphValidator = new GraphValidator();
    private final Gson gson = new Gson();

    public PilotScenarioService(ScenarioCatalog catalog, AgentPlatformService platform,
            DurableOrchestrator orchestrator, DurableStores.ApprovalService approvals,
            WorkspaceLease.Provider leases, ScenarioPorts.CodingRepository coding,
            ScenarioPorts.AnalyticsData analytics, ScenarioPorts.KnowledgeRetrieval knowledge,
            ScenarioArtifact.Repository artifacts, AuditService audit, MeterRegistry meters,
            @Value("${platform.scenarios.analytics.approval-threshold-usd:5.00}") BigDecimal threshold) {
        this.catalog = catalog; this.platform = platform; this.orchestrator = orchestrator;
        this.approvals = approvals; this.leases = leases; this.coding = coding; this.analytics = analytics;
        this.knowledge = knowledge; this.artifacts = artifacts; this.audit = audit; this.meters = meters;
        this.approvalThreshold = threshold;
    }

    public List<RolePack> templates() { return catalog.list(); }

    public AgentRun start(String tenant, String scenario, Map<String, Object> input) {
        RolePack pack = catalog.get(scenario);
        validate(pack);
        AgentRun run = orchestrator.create(tenant, agentId(pack.getName()), ExecutionMode.GRAPH,
                text(input, "goal", "execute " + scenario), pack.getGraph().getLimits().getMaxSteps(),
                pack.getGraph().getLimits().getMaxCostUsd(), Duration.ofMinutes(30));
        try {
            if (ScenarioCatalog.CODING.equals(scenario)) executeCoding(tenant, run, input);
            else if (ScenarioCatalog.ANALYST.equals(scenario)) executeAnalyst(tenant, run, input);
            else executeSupport(tenant, run, input);
        } catch (RuntimeException blocked) {
            block(tenant, run, scenario, blocked);
        }
        return platform.getRun(run.getId());
    }

    public AgentRun continueRun(String tenant, String scenario, String runId) {
        if (!ScenarioCatalog.ANALYST.equals(scenario)) {
            throw new IllegalArgumentException("only data-analyst has a resumable approval boundary");
        }
        AgentRun run = platform.getRun(runId);
        requireTenant(tenant, run);
        boolean approved = approvals.findApprovals(tenant, runId).stream()
                .anyMatch(a -> a.getStatus() == ApprovalRequest.Status.APPROVED);
        if (!approved) throw new IllegalStateException("approved cost request required");
        ScenarioArtifact request = artifacts.findByRun(tenant, runId).stream()
                .filter(a -> "ANALYSIS_REQUEST".equals(a.getType())).findFirst()
                .orElseThrow(() -> new IllegalStateException("analysis request artifact missing"));
        Map<String, Object> input = gson.fromJson(request.getContent(),
                new TypeToken<Map<String, Object>>() {}.getType());
        orchestrator.resume(tenant, runId, key(runId, "resume"));
        finishAnalyst(tenant, run, input);
        return platform.getRun(runId);
    }

    public AgentRun status(String tenant, String runId) {
        AgentRun run = platform.getRun(runId);
        requireTenant(tenant, run);
        return run;
    }

    public List<ScenarioArtifact> artifacts(String tenant, String runId) {
        status(tenant, runId);
        return artifacts.findByRun(tenant, runId);
    }

    private void executeCoding(String tenant, AgentRun run, Map<String, Object> input) {
        String branch = text(input, "branch", "feature/scenario-proposal");
        String command = text(input, "command", "");
        String combined = (branch + " " + command + " " + run.getGoal()).toLowerCase(Locale.ROOT);
        if ("main".equalsIgnoreCase(branch) || "master".equalsIgnoreCase(branch)) {
            throw new SecurityException("direct writes to main/master are forbidden");
        }
        if (combined.contains("--no-verify") || combined.contains("push --force")
                || combined.contains("push -f") || combined.contains("force push")
                || combined.contains("production deploy") || combined.contains("deploy production")) {
            throw new SecurityException("forbidden git/deployment operation");
        }
        String workspace = required(input, "workspace");
        step(tenant, run, "explore", map("mode", "READ_ONLY"));
        try (WorkspaceLease lease = leases.acquire(Paths.get(workspace), run.getId())) {
            String snapshot = coding.exploreReadOnly(lease.getWorkspace(), run.getGoal());
            step(tenant, run, "plan", map("snapshot", snapshot, "strategy", "PLAN_AND_EXECUTE"));
            step(tenant, run, "lease", map("leaseId", lease.getLeaseId(), "isolated", true));
            String patch = coding.proposePatch(lease.getWorkspace(), run.getGoal());
            step(tenant, run, "patch", map("proposalOnly", true, "branch", branch));
            ScenarioPorts.Verification verification = coding.verify(lease.getWorkspace(), patch);
            step(tenant, run, "verify", map("buildPassed", verification.buildPassed,
                    "testsPassed", verification.testsPassed, "output", verification.output));
            if (!verification.buildPassed || !verification.testsPassed) throw new IllegalStateException("verification failed");
            ScenarioPorts.Review review = coding.independentReview(patch, verification);
            step(tenant, run, "review", map("approved", review.approved, "summary", review.summary));
            if (!review.approved) throw new IllegalStateException("independent review rejected");
            artifacts.save(tenant, run.getId(), "PATCH_PROPOSAL", "change.patch", patch);
            artifacts.save(tenant, run.getId(), "PR_DRAFT", "pull-request-draft.json",
                    gson.toJson(map("title", run.getGoal(), "branch", branch, "status", "DRAFT",
                            "externalPrCreated", false, "review", review.summary)));
            step(tenant, run, "pr-draft", map("externalPrCreated", false, "status", "DRAFT"));
            complete(tenant, run, ScenarioCatalog.CODING);
        }
    }

    private void executeAnalyst(String tenant, AgentRun run, Map<String, Object> input) {
        String sql = required(input, "sql");
        int maxRows = number(input, "maxRows", 1000).intValue();
        SqlGuard.GuardedSql guarded = sqlGuard.validate(sql, maxRows);
        step(tenant, run, "sql-guard", map("readOnly", true, "limit", guarded.getLimit()));
        String metric = required(input, "metric");
        step(tenant, run, "metric", map("definition", analytics.metricDefinition(metric)));
        ScenarioPorts.CostEstimate estimate = analytics.estimate(guarded.getSql());
        step(tenant, run, "estimate", map("scannedBytes", estimate.scannedBytes,
                "estimatedUsd", estimate.estimatedUsd));
        artifacts.save(tenant, run.getId(), "ANALYSIS_REQUEST", "analysis-request.json", gson.toJson(input));
        if (estimate.estimatedUsd.compareTo(approvalThreshold) > 0) {
            orchestrator.awaitApproval(tenant, run.getId(), "approval", "ANALYTICS_QUERY_COST",
                    gson.toJson(map("sqlHash", Integer.toHexString(sql.hashCode()),
                            "estimatedUsd", estimate.estimatedUsd)), Duration.ofMinutes(15),
                    key(run.getId(), "approval"));
            metric(ScenarioCatalog.ANALYST, "approval");
            audit(tenant, run, "ANALYTICS_COST_APPROVAL_REQUIRED", "PENDING");
            return;
        }
        finishAnalyst(tenant, run, input);
    }

    private void finishAnalyst(String tenant, AgentRun run, Map<String, Object> input) {
        SqlGuard.GuardedSql guarded = sqlGuard.validate(required(input, "sql"),
                number(input, "maxRows", 1000).intValue());
        ScenarioPorts.QueryResult result = analytics.executeReadOnly(guarded.getSql(), guarded.getLimit());
        step(tenant, run, "query", map("rows", result.rows.size(), "adapter", "CONTROLLED_FAKE"));
        if (result.rows.size() > guarded.getLimit() || result.citations == null || result.citations.isEmpty()) {
            throw new IllegalStateException("result/citation verification failed");
        }
        step(tenant, run, "verify", map("citations", result.citations, "verified", true));
        artifacts.save(tenant, run.getId(), "REPORT", "analysis-report.json",
                gson.toJson(map("metric", required(input, "metric"),
                        "metricDefinition", analytics.metricDefinition(required(input, "metric")),
                        "rows", result.rows, "citations", result.citations, "externalDbConnected", false)));
        step(tenant, run, "report", map("artifact", "analysis-report.json", "verified", true));
        complete(tenant, run, ScenarioCatalog.ANALYST);
    }

    private void executeSupport(String tenant, AgentRun run, Map<String, Object> input) {
        String message = required(input, "message");
        String masked = PII.matcher(message).replaceAll("[PII]");
        step(tenant, run, "pii-mask", map("masked", masked, "piiDetected", !masked.equals(message)));
        List<ScenarioPorts.Citation> citations = knowledge.search(masked);
        if (citations == null || citations.isEmpty()) throw new IllegalStateException("knowledge citation required");
        step(tenant, run, "retrieve", map("citationCount", citations.size(), "adapter", "CONTROLLED_FAKE"));
        boolean sensitive = SENSITIVE.matcher(masked).find();
        step(tenant, run, "compliance", map("sensitiveIntent", sensitive, "autoSend", false));
        String draft = "回复草稿（未发送）：根据知识条目 " + citations.get(0).id + "，我们已记录您的问题。";
        artifacts.save(tenant, run.getId(), "REPLY_DRAFT", "customer-reply-draft.json",
                gson.toJson(map("draft", draft, "citations", citations, "piiMaskedInput", masked,
                        "sent", false, "externalCrmConnected", false)));
        step(tenant, run, "draft", map("artifact", "customer-reply-draft.json", "sent", false));
        double confidence = number(input, "confidence", 0.90).doubleValue();
        step(tenant, run, "confidence", map("confidence", confidence, "threshold", 0.70));
        if (sensitive || confidence < 0.70) {
            step(tenant, run, "handoff", map("required", true, "autoSend", false));
            orchestrator.awaitApproval(tenant, run.getId(), "handoff", "HUMAN_SUPPORT_HANDOFF",
                    gson.toJson(map("sensitive", sensitive, "confidence", confidence)),
                    Duration.ofHours(8), key(run.getId(), "handoff-approval"));
            metric(ScenarioCatalog.SUPPORT, "handoff");
            audit(tenant, run, "SUPPORT_HUMAN_HANDOFF", "PENDING");
            return;
        }
        step(tenant, run, "draft-artifact", map("artifact", "customer-reply-draft.json", "sent", false));
        complete(tenant, run, ScenarioCatalog.SUPPORT);
    }

    private void step(String tenant, AgentRun run, String node, Map<String, Object> state) {
        orchestrator.recordStep(tenant, run.getId(), node, gson.toJson(state), BigDecimal.ZERO,
                key(run.getId(), node));
    }

    private void complete(String tenant, AgentRun run, String scenario) {
        orchestrator.complete(tenant, run.getId(), "{\"completed\":true}", key(run.getId(), "complete"));
        metric(scenario, "success");
        audit(tenant, run, "SCENARIO_COMPLETED", "ALLOW");
    }

    private void block(String tenant, AgentRun run, String scenario, RuntimeException blocked) {
        artifacts.save(tenant, run.getId(), "SAFETY_BLOCK", "safety-block.json",
                gson.toJson(map("blocked", true, "reason", blocked.getMessage())));
        orchestrator.fail(tenant, run.getId(), blocked.getMessage(), key(run.getId(), "failed"));
        metric(scenario, "blocked");
        audit(tenant, run, "SCENARIO_BLOCKED", "DENY");
    }

    private void validate(RolePack pack) {
        GraphValidationResult validation = graphValidator.validate(pack.getGraph());
        if (!validation.isValid()) throw new IllegalStateException("invalid graph: " + validation.getErrors());
    }

    private String agentId(String name) {
        return platform.listAgents().stream().filter(a -> a.getName().equals(name))
                .map(AgentDefinition::getId).findFirst()
                .orElseThrow(() -> new IllegalStateException("seeded agent missing: " + name));
    }

    private void audit(String tenant, AgentRun run, String operation, String decision) {
        audit.append(tenant, "SYSTEM", "pilot-scenario-service", operation, "SCENARIO_RUN",
                run.getId(), decision, run.getGoal(), null, run.getId());
    }

    private void metric(String scenario, String outcome) {
        meters.counter("pilot.scenario.runs", "scenario", scenario, "outcome", outcome).increment();
    }

    private static String key(String runId, String step) { return "scenario:" + runId + ":" + step; }
    private static void requireTenant(String tenant, AgentRun run) {
        if (!run.getTenantId().equals(tenant)) throw new IllegalArgumentException("run not found");
    }
    private static String required(Map<String, Object> input, String name) {
        String value = text(input, name, null);
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " required");
        return value.trim();
    }
    private static String text(Map<String, Object> input, String name, String fallback) {
        Object value = input == null ? null : input.get(name);
        return value == null ? fallback : value.toString();
    }
    private static Number number(Map<String, Object> input, String name, Number fallback) {
        Object value = input == null ? null : input.get(name);
        if (value == null) return fallback;
        if (value instanceof Number) return (Number) value;
        return new BigDecimal(value.toString());
    }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i].toString(), pairs[i + 1]);
        return map;
    }
}
