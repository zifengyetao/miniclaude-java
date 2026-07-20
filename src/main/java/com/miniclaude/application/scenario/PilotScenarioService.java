package com.miniclaude.application.scenario;

import com.google.gson.Gson;
import com.miniclaude.application.governance.AuditService;
import com.miniclaude.application.platform.AgentPlatformService;
import com.miniclaude.application.platform.GraphRunner;
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
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

@Service
/**
 * 三个试点场景的应用编排器。
 *
 * <p>服务只通过场景端口调用受控 fake，并把每个决策写入持久化步骤和制品：
 * Coding 在允许目录的独占 lease 中只生成补丁/PR 草稿；分析先过 SQL 只读 guard；
 * 客服先脱敏且永不自动发送，敏感意图或低置信度必须转人工。</p>
 */
public class PilotScenarioService {
    private static final Pattern PII = Pattern.compile(
            "(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private static final Pattern SENSITIVE = Pattern.compile(
            "(?i)退款|投诉|法律|起诉|自杀|伤害|fraud|chargeback|legal|suicide");
    private final ScenarioCatalog catalog;
    private final AgentPlatformService platform;
    private final DurableOrchestrator orchestrator;
    private final GraphRunner graphRunner;
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
            DurableOrchestrator orchestrator, GraphRunner graphRunner,
            DurableStores.ApprovalService approvals,
            WorkspaceLease.Provider leases, ScenarioPorts.CodingRepository coding,
            ScenarioPorts.AnalyticsData analytics, ScenarioPorts.KnowledgeRetrieval knowledge,
            ScenarioArtifact.Repository artifacts, AuditService audit, MeterRegistry meters,
            @Value("${platform.scenarios.analytics.approval-threshold-usd:5.00}") BigDecimal threshold) {
        this.catalog = catalog; this.platform = platform; this.orchestrator = orchestrator;
        this.graphRunner = graphRunner;
        this.approvals = approvals; this.leases = leases; this.coding = coding; this.analytics = analytics;
        this.knowledge = knowledge; this.artifacts = artifacts; this.audit = audit; this.meters = meters;
        this.approvalThreshold = threshold;
    }

    /** @return 试点场景 RolePack 列表 */
    public List<RolePack> templates() { return catalog.list(); }

    /**
     * 启动试点场景 Run 并同步执行至完成、审批暂停或安全阻断。
     *
     * @param scenario {@link ScenarioCatalog} 中已注册 ID
     * @implNote 副作用：Run、步骤、制品、可能的审批与指标；外部系统均为 Fake
     */
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
            // why：任何校验或适配器异常都统一落为可审计的安全阻断，不能留下“运行中”假象。
            block(tenant, run, scenario, blocked);
        }
        return platform.getRun(run.getId());
    }

    /**
     * 继续已暂停的 Run（仅 data-analyst 成本审批场景）。
     *
     * @throws IllegalArgumentException 非 analyst 场景
     * @throws IllegalStateException Run/Graph/审批与当前 Analyst 请求不完全绑定
     */
    public AgentRun continueRun(String tenant, String scenario, String runId) {
        if (!ScenarioCatalog.ANALYST.equals(scenario)) {
            throw new IllegalArgumentException("only data-analyst has a resumable approval boundary");
        }
        AgentRun run = platform.getRun(runId);
        requireTenant(tenant, run);
        RolePack pack = catalog.get(ScenarioCatalog.ANALYST);
        if (!run.getAgentId().equals(agentId(pack.getName()))) {
            throw new IllegalStateException("run is not owned by data-analyst");
        }
        Map<String, Object> state = graphRunner.loadCheckpointState(pack.getGraph(), tenant, runId);
        if (!"approval".equals(state.get("_completedNode")) || !"query".equals(state.get("_nextNode"))) {
            throw new IllegalStateException("run is not at analyst approval boundary");
        }
        String expectedParameters = required(state, "approvalParameters");
        boolean approved = approvals.findApprovals(tenant, runId).stream()
                .anyMatch(approval -> approval.getStatus() == ApprovalRequest.Status.APPROVED
                        && "approval".equals(approval.getStepId())
                        && "ANALYTICS_QUERY_COST".equals(approval.getActionType())
                        && expectedParameters.equals(approval.getActionParameters()));
        if (!approved) throw new IllegalStateException("bound analyst cost approval required");

        orchestrator.resume(tenant, runId, key(runId, "resume"));
        try {
            GraphRunner.Result result = graphRunner.resume(pack.getGraph(), tenant, runId,
                    this::executeAnalystNode);
            if (result.isCompleted()) {
                metric(ScenarioCatalog.ANALYST, "success");
                audit(tenant, run, "SCENARIO_COMPLETED", "ALLOW");
            }
        } catch (RuntimeException failed) {
            orchestrator.fail(tenant, runId, failed.getMessage(), key(runId, "resume-failed"));
            audit(tenant, run, "SCENARIO_BLOCKED", "DENY");
            throw failed;
        }
        return platform.getRun(runId);
    }

    /**
     * 查询 Run 并校验租户。
     *
     * @throws IllegalArgumentException 租户不匹配
     */
    public AgentRun status(String tenant, String runId) {
        AgentRun run = platform.getRun(runId);
        requireTenant(tenant, run);
        return run;
    }

    /** 列出 Run 场景制品（先 {@link #status} 校验租户）。 */
    public List<ScenarioArtifact> artifacts(String tenant, String runId) {
        status(tenant, runId);
        return artifacts.findByRun(tenant, runId);
    }

    private void executeCoding(String tenant, AgentRun run, Map<String, Object> input) {
        String branch = text(input, "branch", "feature/scenario-proposal");
        String command = text(input, "command", "");
        String combined = (branch + " " + command + " " + run.getGoal()).toLowerCase(Locale.ROOT);
        // why：主分支和绕过审查/强推/生产部署会把“提案型”场景升级成不可逆外部动作。
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
        // 独占 lease 将并发运行隔离到不同工作区；关闭 lease 后立即释放占用。
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
            // 终点只保存 patch 和 PR 草稿，明确不应用补丁、不创建外部 PR。
            artifacts.save(tenant, run.getId(), "PATCH_PROPOSAL", "change.patch", patch);
            artifacts.save(tenant, run.getId(), "PR_DRAFT", "pull-request-draft.json",
                    gson.toJson(map("title", run.getGoal(), "branch", branch, "status", "DRAFT",
                            "externalPrCreated", false, "review", review.summary)));
            step(tenant, run, "pr-draft", map("externalPrCreated", false, "status", "DRAFT"));
            complete(tenant, run, ScenarioCatalog.CODING);
        }
    }

    private void executeAnalyst(String tenant, AgentRun run, Map<String, Object> input) {
        required(input, "sql");
        required(input, "metric");
        // 原始请求作为审计制品保存；恢复的执行位置和状态只以 Graph checkpoint 为准。
        artifacts.save(tenant, run.getId(), "ANALYSIS_REQUEST", "analysis-request.json", gson.toJson(input));
        GraphRunner.Result result = graphRunner.start(catalog.get(ScenarioCatalog.ANALYST).getGraph(),
                tenant, run.getId(), input, this::executeAnalystNode);
        if (result.isSuspended()) {
            metric(ScenarioCatalog.ANALYST, "approval");
            audit(tenant, run, "ANALYTICS_COST_APPROVAL_REQUIRED", "PENDING");
        } else if (result.isCompleted()) {
            metric(ScenarioCatalog.ANALYST, "success");
            audit(tenant, run, "SCENARIO_COMPLETED", "ALLOW");
        }
    }

    private GraphRunner.NodeResult executeAnalystNode(GraphRunner.NodeContext context) {
        Map<String, Object> state = context.getState();
        String node = context.getNode().getId();
        switch (node) {
            case "sql-guard": {
                // guard 在任何估算或查询前执行；恢复后 query 节点还会再次校验原始 SQL。
                SqlGuard.GuardedSql guarded = sqlGuard.validate(required(state, "sql"),
                        number(state, "maxRows", 1000).intValue());
                return GraphRunner.NodeResult.continueWith(map(
                        "guardedSql", guarded.getSql(), "limit", guarded.getLimit(), "readOnly", true));
            }
            case "metric":
                return GraphRunner.NodeResult.continueWith(map(
                        "metricDefinition", analytics.metricDefinition(required(state, "metric"))));
            case "estimate": {
                ScenarioPorts.CostEstimate estimate = analytics.estimate(required(state, "guardedSql"));
                boolean approvalRequired = estimate.estimatedUsd.compareTo(approvalThreshold) > 0;
                return GraphRunner.NodeResult.continueWith(map(
                        "scannedBytes", estimate.scannedBytes,
                        "estimatedUsd", estimate.estimatedUsd,
                        "approvalRequired", approvalRequired));
            }
            case "approval": {
                String parameters = gson.toJson(map(
                        "sqlHash", sha256(required(state, "guardedSql")),
                        "estimatedUsd", state.get("estimatedUsd")));
                return GraphRunner.NodeResult.awaitApproval(
                        map("approvalRequired", true, "approvalParameters", parameters),
                        "ANALYTICS_QUERY_COST", parameters, Duration.ofMinutes(15));
            }
            case "query": {
                // 不信任审批等待期间持久状态天然安全，执行外部读取前重新从原始输入校验。
                SqlGuard.GuardedSql guarded = sqlGuard.validate(required(state, "sql"),
                        number(state, "maxRows", 1000).intValue());
                ScenarioPorts.QueryResult result = analytics.executeReadOnly(
                        guarded.getSql(), guarded.getLimit());
                return GraphRunner.NodeResult.continueWith(map(
                        "rows", result.rows,
                        "rowCount", result.rows.size(),
                        "citations", result.citations,
                        "adapter", "CONTROLLED_FAKE"));
            }
            case "verify": {
                List<?> rows = list(state, "rows");
                List<?> citations = list(state, "citations");
                int limit = number(state, "limit", 1000).intValue();
                if (rows.size() > limit || citations.isEmpty()) {
                    throw new IllegalStateException("result/citation verification failed");
                }
                return GraphRunner.NodeResult.continueWith(map("verified", true));
            }
            case "report":
                if (!Boolean.TRUE.equals(state.get("verified"))) {
                    throw new IllegalStateException("verified result required");
                }
                String report = gson.toJson(map(
                        "metric", required(state, "metric"),
                        "metricDefinition", state.get("metricDefinition"),
                        "rows", list(state, "rows"),
                        "citations", list(state, "citations"),
                        "externalDbConnected", false));
                return GraphRunner.NodeResult.terminalWith(
                        map("artifact", "analysis-report.json", "reportVerified", true),
                        () -> artifacts.saveIdempotent(
                                context.getTenantId(), context.getRunId(), "REPORT",
                                "analysis-report.json", report,
                                "graph:" + context.getRunId() + ":report:artifact"));
            default:
                throw new IllegalStateException("unsupported analyst node: " + node);
        }
    }

    private void executeSupport(String tenant, AgentRun run, Map<String, Object> input) {
        String message = required(input, "message");
        // 原始 PII 不进入检索、步骤状态或制品；后续链路只使用 masked 文本。
        String masked = PII.matcher(message).replaceAll("[PII]");
        step(tenant, run, "pii-mask", map("masked", masked, "piiDetected", !masked.equals(message)));
        List<ScenarioPorts.Citation> citations = knowledge.search(masked);
        if (citations == null || citations.isEmpty()) throw new IllegalStateException("knowledge citation required");
        step(tenant, run, "retrieve", map("citationCount", citations.size(), "adapter", "CONTROLLED_FAKE"));
        boolean sensitive = SENSITIVE.matcher(masked).find();
        step(tenant, run, "compliance", map("sensitiveIntent", sensitive, "autoSend", false));
        String draft = "回复草稿（未发送）：根据知识条目 " + citations.get(0).id + "，我们已记录您的问题。";
        // 客服产物始终是未发送草稿；当前端口也没有 CRM 发送能力。
        artifacts.save(tenant, run.getId(), "REPLY_DRAFT", "customer-reply-draft.json",
                gson.toJson(map("draft", draft, "citations", citations, "piiMaskedInput", masked,
                        "sent", false, "externalCrmConnected", false)));
        step(tenant, run, "draft", map("artifact", "customer-reply-draft.json", "sent", false));
        double confidence = number(input, "confidence", 0.90).doubleValue();
        step(tenant, run, "confidence", map("confidence", confidence, "threshold", 0.70));
        if (sensitive || confidence < 0.70) {
            // why：高风险意图或低置信度不能依赖自动回复，必须暂停并交给人工处理。
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
    private static List<?> list(Map<String, Object> input, String name) {
        Object value = input == null ? null : input.get(name);
        if (!(value instanceof List)) throw new IllegalStateException(name + " list required");
        return (List<?>) value;
    }
    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte item : digest) result.append(String.format("%02x", item & 0xff));
            return result.toString();
        } catch (Exception e) {
            throw new IllegalStateException("cannot hash sql", e);
        }
    }
    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) map.put(pairs[i].toString(), pairs[i + 1]);
        return map;
    }
}
