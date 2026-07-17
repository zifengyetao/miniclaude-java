package com.miniclaude.application.scenario;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.miniclaude.application.governance.AuditService;
import com.miniclaude.application.platform.AgentPlatformService;
import com.miniclaude.domain.durable.ApprovalRequest;
import com.miniclaude.domain.durable.DurableOrchestrator;
import com.miniclaude.domain.durable.DurableStores;
import com.miniclaude.domain.platform.AgentDefinition;
import com.miniclaude.domain.platform.AgentRun;
import com.miniclaude.domain.platform.ExecutionMode;
import com.miniclaude.domain.scenario.RegulatedScenarioPorts;
import com.miniclaude.domain.scenario.RegulatedSimulationPolicy;
import com.miniclaude.domain.scenario.RolePack;
import com.miniclaude.domain.scenario.ScenarioArtifact;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
public final class RegulatedScenarioService {
    private static final Pattern PII = Pattern.compile(
            "(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private final RegulatedScenarioCatalog catalog;
    private final RegulatedSimulationPolicy policy;
    private final AgentPlatformService platform;
    private final DurableOrchestrator orchestrator;
    private final DurableStores.ApprovalService approvals;
    private final ScenarioArtifact.Repository artifacts;
    private final RegulatedScenarioPorts.InvestigationEvidence investigation;
    private final RegulatedScenarioPorts.InvestigationVerifier verifier;
    private final RegulatedScenarioPorts.TradingResearch trading;
    private final AuditService audit;
    private final Gson gson = new Gson();

    public RegulatedScenarioService(RegulatedScenarioCatalog catalog,
            RegulatedSimulationPolicy policy, AgentPlatformService platform,
            DurableOrchestrator orchestrator, DurableStores.ApprovalService approvals,
            ScenarioArtifact.Repository artifacts,
            RegulatedScenarioPorts.InvestigationEvidence investigation,
            RegulatedScenarioPorts.InvestigationVerifier verifier,
            RegulatedScenarioPorts.TradingResearch trading, AuditService audit) {
        this.catalog = catalog; this.policy = policy; this.platform = platform;
        this.orchestrator = orchestrator; this.approvals = approvals; this.artifacts = artifacts;
        this.investigation = investigation; this.verifier = verifier;
        this.trading = trading; this.audit = audit;
    }

    public List<RolePack> templates() { return catalog.list(); }

    public AgentRun start(String tenant, String scenario, Map<String, Object> input,
                          String idempotencyKey) {
        Instant deadline = deadline(input);
        policy.guard(tenant, deadline);
        String previous = policy.findRun(tenant, idempotencyKey);
        if (previous != null) return status(tenant, previous);
        RolePack pack = catalog.get(scenario);
        String proposer = required(input, "proposer");
        AgentRun run = orchestrator.create(tenant, agentId(pack.getName()), ExecutionMode.GRAPH,
                text(input, "goal", "regulated simulation " + scenario),
                pack.getGraph().getLimits().getMaxSteps(), pack.getGraph().getLimits().getMaxCostUsd(),
                Duration.between(Instant.now(), deadline));
        policy.rememberRun(tenant, idempotencyKey, run.getId());
        artifacts.save(tenant, run.getId(), "REGULATED_CONTEXT", "regulated-context.json",
                gson.toJson(map("scenario", scenario, "proposer", proposer,
                        "deadline", deadline.toString(), "domain", RegulatedSimulationPolicy.DOMAIN,
                        "evolutionMaxLevel", "L1", "input", input)));
        audit(tenant, run, proposer, "REGULATED_RUN_STARTED", "ALLOW");
        try {
            if (RegulatedScenarioCatalog.INVESTIGATION.equals(scenario)) {
                executeInvestigation(tenant, run, input, deadline);
            } else {
                executeTrading(tenant, run, input, deadline);
            }
        } catch (RuntimeException blocked) {
            block(tenant, run, scenario, blocked);
        }
        return status(tenant, run.getId());
    }

    private void executeInvestigation(String tenant, AgentRun run,
                                      Map<String, Object> input, Instant deadline) {
        policy.guard(tenant, deadline);
        String requestedAction = text(input, "requestedAction", "REVIEW").toUpperCase(Locale.ROOT);
        if (!"REVIEW".equals(requestedAction) && !"ESCALATE".equals(requestedAction)) {
            throw new SecurityException("automatic reject/deny/ban/freeze decision is forbidden");
        }
        String subject = required(input, "subjectRef");
        String narrative = PII.matcher(text(input, "narrative", "")).replaceAll("[PII]");
        step(tenant, run, "pii-mask", map("maskedNarrative", narrative, "rawPiiPersisted", false));
        RegulatedScenarioPorts.Score rules = investigation.ruleScore(subject);
        RegulatedScenarioPorts.Score model = investigation.modelScore(subject);
        List<RegulatedScenarioPorts.Evidence> evidence = investigation.graphAndCaseEvidence(subject);
        step(tenant, run, "score-and-evidence", map("ruleScore", rules, "modelScore", model,
                "evidence", evidence, "adapters", "DETERMINISTIC_FAKE_ONLY"));
        String recommendation = rules.value.add(model.value)
                .compareTo(new BigDecimal("1.20")) >= 0 ? "ESCALATE" : "REVIEW";
        RegulatedScenarioPorts.Verification verification = verifier.verify(recommendation, evidence);
        if (!verification.passed) throw new SecurityException("independent verification failed");
        ScenarioArtifact casePackage = artifacts.save(tenant, run.getId(),
                "INVESTIGATION_CASE_PACKAGE", "investigation-case-package.json",
                gson.toJson(map("subjectRef", subject, "maskedNarrative", narrative,
                        "scores", map("rule", rules, "model", model),
                        "evidenceProvenance", evidence, "recommendation", recommendation,
                        "allowedRecommendations", new String[] {"REVIEW", "ESCALATE"},
                        "automaticAdverseAction", false, "verifier", verification,
                        "simulationOnly", true)));
        step(tenant, run, "independent-verifier", map("passed", true,
                "verifier", verification.verifier, "casePackageHash", casePackage.getContentHash()));
        requestFourEyes(tenant, run, "INVESTIGATION_RECOMMENDATION",
                casePackage.getContentHash(), deadline);
    }

    private void executeTrading(String tenant, AgentRun run,
                                Map<String, Object> input, Instant deadline) {
        policy.guard(tenant, deadline);
        String instrument = required(input, "instrument").toUpperCase(Locale.ROOT);
        String portfolio = required(input, "portfolioRef");
        BigDecimal quantity = decimal(input, "quantity", null);
        RegulatedScenarioPorts.MarketSnapshot market = trading.market(instrument);
        RegulatedScenarioPorts.PositionSnapshot position = trading.position(portfolio, instrument);
        long age = number(input, "marketDataAgeSeconds", 0).longValue();
        boolean marketOpen = bool(input, "marketOpen", true);
        BigDecimal stressLossPct = decimal(input, "stressLossPct", new BigDecimal("0.20"));
        step(tenant, run, "readonly-inputs", map("market", market,
                "research", trading.research(instrument), "position", position,
                "ports", "READ_ONLY_DETERMINISTIC_FAKE"));
        ScenarioArtifact proposal = artifacts.save(tenant, run.getId(), "TRADE_PROPOSAL",
                "trade-proposal.json", gson.toJson(map(
                        "instrument", instrument, "side", quantity.signum() >= 0 ? "BUY" : "SELL",
                        "quantity", quantity.abs(), "assumptions",
                        new String[] {"fake market snapshot remains representative", "human validates thesis"},
                        "risk", map("marketRisk", "SIMULATED", "liquidityRisk", "SIMULATED"),
                        "scenarioStress", map("lossPercent", stressLossPct,
                                "estimatedLoss", quantity.abs().multiply(market.price)
                                        .multiply(stressLossPct.abs())),
                        "simulationOnly", true)));
        policy.validatePreTrade(instrument, quantity, position.quantity, market.price,
                stressLossPct, marketOpen, age);
        step(tenant, run, "pre-trade-risk", map("passed", true,
                "instrumentAllowlist", policy.instrumentAllowlist(),
                "notionalLimit", RegulatedSimulationPolicy.MAX_NOTIONAL,
                "positionLimit", RegulatedSimulationPolicy.MAX_POSITION,
                "lossLimit", RegulatedSimulationPolicy.MAX_STRESS_LOSS,
                "marketAgeSeconds", age, "proposalHash", proposal.getContentHash()));
        requestFourEyes(tenant, run, "OMS_ORDER_DRAFT", proposal.getContentHash(), deadline);
    }

    private void requestFourEyes(String tenant, AgentRun run, String actionType,
                                 String artifactHash, Instant deadline) {
        Duration ttl = Duration.between(Instant.now(), deadline);
        String parameters = gson.toJson(map("artifactHash", artifactHash,
                "fourEyes", true, "requiredApprovals", 2, "simulationOnly", true));
        orchestrator.awaitApproval(tenant, run.getId(), "four-eyes-1", actionType,
                parameters, ttl, key(run.getId(), "four-eyes-1"));
        approvals.request(tenant, run.getId(), "four-eyes-2", actionType, parameters, ttl);
        audit(tenant, run, "SYSTEM", "FOUR_EYES_REQUIRED", "PENDING");
    }

    public ApprovalRequest decide(String tenant, String runId, String approvalId,
                                  String actor, String decision, String reason) {
        AgentRun run = status(tenant, runId);
        Context context = context(tenant, runId);
        policy.guard(tenant, context.deadline);
        if (actor.equals(context.proposer)) {
            throw new SecurityException("proposer cannot approve own regulated proposal");
        }
        ApprovalRequest request = approvals.find(tenant, approvalId)
                .orElseThrow(() -> new IllegalArgumentException("approval not found"));
        if (!runId.equals(request.getRunId())) throw new IllegalArgumentException("approval not found");
        if ("APPROVED".equals(decision)) {
            boolean duplicate = approvals.findApprovals(tenant, runId).stream()
                    .filter(a -> a.getStatus() == ApprovalRequest.Status.APPROVED)
                    .anyMatch(a -> actor.equals(a.getDecidedBy()));
            if (duplicate) throw new SecurityException("two different approvers are required");
        }
        ApprovalRequest decided = approvals.decide(tenant, approvalId, request.getActionParameters(),
                ApprovalRequest.Status.valueOf(decision), actor, reason == null ? "" : reason);
        audit(tenant, run, actor, "REGULATED_APPROVAL_" + decision, decision);
        return decided;
    }

    public AgentRun continueRun(String tenant, String scenario, String runId) {
        catalog.get(scenario);
        AgentRun run = status(tenant, runId);
        Context context = context(tenant, runId);
        if (!scenario.equals(context.scenario)) throw new IllegalArgumentException("scenario mismatch");
        policy.guard(tenant, context.deadline);
        List<ApprovalRequest> approved = approvals.findApprovals(tenant, runId).stream()
                .filter(a -> a.getStatus() == ApprovalRequest.Status.APPROVED)
                .collect(Collectors.toList());
        Set<String> actors = approved.stream().map(ApprovalRequest::getDecidedBy)
                .collect(Collectors.toCollection(HashSet::new));
        if (approved.size() != 2 || actors.size() != 2 || actors.contains(context.proposer)) {
            throw new SecurityException("two distinct non-proposer approvals are required");
        }
        orchestrator.resume(tenant, runId, key(runId, "regulated-resume"));
        if (RegulatedScenarioCatalog.INVESTIGATION.equals(scenario)) {
            ScenarioArtifact source = artifact(tenant, runId, "INVESTIGATION_CASE_PACKAGE");
            Map<String, Object> packageData = jsonMap(source.getContent());
            String recommendation = String.valueOf(packageData.get("recommendation"));
            if (!"REVIEW".equals(recommendation) && !"ESCALATE".equals(recommendation)) {
                throw new SecurityException("unsafe recommendation blocked");
            }
            artifacts.save(tenant, runId, "VERIFIED_RECOMMENDATION",
                    "verified-recommendation.json", gson.toJson(map(
                            "recommendation", recommendation, "approvedBy", actors,
                            "automaticAdverseAction", false, "simulationOnly", true)));
        } else {
            ScenarioArtifact proposal = artifact(tenant, runId, "TRADE_PROPOSAL");
            artifacts.save(tenant, runId, "OMS_ORDER_DRAFT", "oms-order-draft.json",
                    gson.toJson(map("proposalHash", proposal.getContentHash(), "approvedBy", actors,
                            "status", "DRAFT", "submitted", false, "placeOrderCalled", false,
                            "submitCapability", false, "credentialsPresent", false,
                            "adapter", "NO_OMS_ADAPTER_BY_DESIGN", "simulationOnly", true)));
        }
        orchestrator.complete(tenant, runId, "{\"regulatedSimulationCompleted\":true}",
                key(runId, "regulated-complete"));
        audit(tenant, run, "SYSTEM", "REGULATED_RUN_COMPLETED", "ALLOW");
        return status(tenant, runId);
    }

    public AgentRun status(String tenant, String runId) {
        AgentRun run = platform.getRun(runId);
        if (!tenant.equals(run.getTenantId())) throw new IllegalArgumentException("run not found");
        return run;
    }

    public List<ScenarioArtifact> artifacts(String tenant, String runId) {
        status(tenant, runId);
        return artifacts.findByRun(tenant, runId);
    }

    public List<ApprovalRequest> approvalStage(String tenant, String runId) {
        status(tenant, runId);
        return approvals.findApprovals(tenant, runId);
    }

    public boolean setKillSwitch(String tenant, boolean active, String actor) {
        boolean value = policy.setKilled(tenant, active);
        audit.append(tenant, "USER", actor, "REGULATED_KILL_SWITCH", "SECURITY_DOMAIN",
                RegulatedSimulationPolicy.DOMAIN, active ? "DENY" : "ALLOW",
                Boolean.toString(active), null, null);
        return value;
    }

    public boolean killSwitch(String tenant) { return policy.isKilled(tenant); }

    private void step(String tenant, AgentRun run, String node, Map<String, Object> state) {
        orchestrator.recordStep(tenant, run.getId(), node, gson.toJson(state), BigDecimal.ZERO,
                key(run.getId(), node));
        audit(tenant, run, "SYSTEM", "REGULATED_STEP_" + node.toUpperCase(Locale.ROOT), "ALLOW");
    }

    private void block(String tenant, AgentRun run, String scenario, RuntimeException error) {
        artifacts.save(tenant, run.getId(), "SAFETY_BLOCK", "regulated-safety-block.json",
                gson.toJson(map("scenario", scenario, "blocked", true, "reason", error.getMessage(),
                        "simulationOnly", true)));
        orchestrator.fail(tenant, run.getId(), error.getMessage(), key(run.getId(), "blocked"));
        audit(tenant, run, "SYSTEM", "REGULATED_SAFETY_BLOCK", "DENY");
    }

    private Context context(String tenant, String runId) {
        Map<String, Object> value = jsonMap(artifact(tenant, runId, "REGULATED_CONTEXT").getContent());
        return new Context(String.valueOf(value.get("scenario")), String.valueOf(value.get("proposer")),
                Instant.parse(String.valueOf(value.get("deadline"))));
    }

    private ScenarioArtifact artifact(String tenant, String runId, String type) {
        return artifacts.findByRun(tenant, runId).stream().filter(a -> type.equals(a.getType()))
                .findFirst().orElseThrow(() -> new IllegalStateException(type + " artifact missing"));
    }

    private Map<String, Object> jsonMap(String json) {
        return gson.fromJson(json, new TypeToken<Map<String, Object>>() {}.getType());
    }

    private String agentId(String name) {
        return platform.listAgents().stream().filter(a -> name.equals(a.getName()))
                .map(AgentDefinition::getId).findFirst()
                .orElseThrow(() -> new IllegalStateException("seeded regulated agent missing: " + name));
    }

    private void audit(String tenant, AgentRun run, String actor, String operation, String decision) {
        audit.append(tenant, "SYSTEM".equals(actor) ? "SYSTEM" : "USER", actor, operation,
                "REGULATED_SCENARIO_RUN", run.getId(), decision, run.getGoal(), run.getId(), run.getId());
    }

    private static Instant deadline(Map<String, Object> input) {
        Number seconds = number(input, "deadlineSeconds", 1800);
        if (seconds.longValue() <= 0 || seconds.longValue() > 3600) {
            throw new IllegalArgumentException("deadlineSeconds must be between 1 and 3600");
        }
        return Instant.now().plusSeconds(seconds.longValue());
    }

    private static String required(Map<String, Object> input, String name) {
        String value = text(input, name, null);
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " required");
        return value.trim();
    }

    private static String text(Map<String, Object> input, String name, String fallback) {
        Object value = input == null ? null : input.get(name);
        return value == null ? fallback : String.valueOf(value);
    }

    private static Number number(Map<String, Object> input, String name, Number fallback) {
        Object value = input == null ? null : input.get(name);
        if (value == null) return fallback;
        return value instanceof Number ? (Number) value : new BigDecimal(String.valueOf(value));
    }

    private static BigDecimal decimal(Map<String, Object> input, String name, BigDecimal fallback) {
        Number value = number(input, name, fallback);
        if (value == null) throw new IllegalArgumentException(name + " required");
        return value instanceof BigDecimal ? (BigDecimal) value : new BigDecimal(value.toString());
    }

    private static boolean bool(Map<String, Object> input, String name, boolean fallback) {
        Object value = input == null ? null : input.get(name);
        return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) result.put(String.valueOf(pairs[i]), pairs[i + 1]);
        return result;
    }

    private static String key(String runId, String step) { return "regulated:" + runId + ":" + step; }

    private static final class Context {
        private final String scenario;
        private final String proposer;
        private final Instant deadline;
        private Context(String scenario, String proposer, Instant deadline) {
            this.scenario = scenario; this.proposer = proposer; this.deadline = deadline;
        }
    }
}
