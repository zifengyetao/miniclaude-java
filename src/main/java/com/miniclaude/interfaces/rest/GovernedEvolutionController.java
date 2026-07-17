package com.miniclaude.interfaces.rest;

import com.miniclaude.application.governance.AntiRotService;
import com.miniclaude.application.governance.GovernedEvolutionService;
import com.miniclaude.domain.governance.VersionedAsset;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 受控进化 HTTP 边界。
 *
 * <p>端点按候选状态机拆分为观察、提案、评测、复核、shadow、canary、晋升和回滚；
 * 控制器不暴露任意状态更新或直接写已发布资产的接口。即使客户端伪造 level/automatic，
 * 服务层仍会重验 L0-L3 边界、职责分离、owner 批准与 L3 allowlist。</p>
 */
@RestController
@RequestMapping("/api/v1/governance/evolution")
public class GovernedEvolutionController {
    private final GovernedEvolutionService evolution;
    private final AntiRotService antiRot;

    public GovernedEvolutionController(GovernedEvolutionService evolution, AntiRotService antiRot) {
        this.evolution = evolution;
        this.antiRot = antiRot;
    }

    @PostMapping("/observations")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> observe(@RequestHeader("X-Tenant-Id") String tenant,
                                       @RequestHeader("X-Actor-Id") String actor,
                                       @RequestBody ObservationRequest body) {
        return evolution.observe(tenant, body.sourceType, body.sourceId, body.traceId, body.runId,
                body.attributionCategory, body.summary, body.evidence, actor);
    }

    @GetMapping("/observations")
    public List<Map<String, Object>> observations(@RequestHeader("X-Tenant-Id") String tenant) {
        return evolution.observations(tenant);
    }

    @PostMapping("/candidates")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> propose(@RequestHeader("X-Tenant-Id") String tenant,
                                       @RequestHeader("X-Actor-Id") String actor,
                                       @RequestBody ProposalRequest body) {
        return evolution.propose(tenant, body.observationId, body.level,
                VersionedAsset.Type.valueOf(body.assetType.toUpperCase()), body.changeClass, body.assetKey,
                body.proposedVersion, body.parentAssetId, body.applicability, body.riskClass,
                body.ownerId, body.regulated, actor);
    }

    @GetMapping("/candidates")
    public List<Map<String, Object>> candidates(@RequestHeader("X-Tenant-Id") String tenant) {
        return evolution.candidates(tenant);
    }

    @PostMapping("/candidates/{id}/evaluate")
    public Map<String, Object> evaluate(@PathVariable String id,
                                        @RequestHeader("X-Actor-Id") String evaluator,
                                        @RequestBody CandidateEvaluationRequest body) {
        // hidden holdout 仅传递隔离引用；API 不接受隐藏样本正文，避免候选生成方获知测试答案。
        return evolution.evaluate(id, evaluator, body.trainingSetRef, body.regressionSetRef,
                body.hiddenHoldoutRef, body.suiteId, body.manifestId, body.metrics, body.safetyPassed);
    }

    @PostMapping("/candidates/{id}/review")
    public Map<String, Object> review(@PathVariable String id,
                                      @RequestHeader("X-Actor-Id") String reviewer,
                                      @RequestBody ReviewRequest body) {
        return evolution.review(id, reviewer, body.reviewerRole, body.decision, body.comment);
    }

    @PostMapping("/candidates/{id}/shadow")
    public Map<String, Object> shadow(@PathVariable String id,
                                      @RequestHeader("X-Actor-Id") String actor,
                                      @RequestBody MetricsRequest body) {
        return evolution.shadow(id, actor, body.metrics);
    }

    @PostMapping("/candidates/{id}/canary")
    public Map<String, Object> canary(@PathVariable String id,
                                      @RequestHeader("X-Actor-Id") String actor,
                                      @RequestBody CanaryRequest body) {
        return evolution.canary(id, body.trafficPercent, actor, body.metrics);
    }

    @PostMapping("/candidates/{id}/promote")
    public Map<String, Object> promote(@PathVariable String id,
                                       @RequestHeader("X-Actor-Id") String actor,
                                       @RequestBody PromoteRequest body) {
        return evolution.promote(id, body.automatic, actor);
    }

    @PostMapping("/candidates/{id}/rollback")
    public Map<String, Object> rollback(@PathVariable String id,
                                        @RequestHeader("X-Actor-Id") String actor,
                                        @RequestBody RollbackRequest body) {
        // 回滚撤销晋升版本并保留证据，不删除候选、rollout 或父资产。
        return evolution.rollback(id, actor, body.reason);
    }

    @PostMapping("/anti-rot/scan")
    public List<Map<String, Object>> antiRotScan(@RequestHeader("X-Tenant-Id") String tenant,
                                                 @RequestHeader("X-Actor-Id") String actor,
                                                 @RequestParam(required = false) String currentModel) {
        // 扫描只报告风险；没有自动删除、覆盖或合并生产资产的能力。
        return antiRot.scan(tenant, currentModel, actor);
    }

    @GetMapping("/anti-rot/findings")
    public List<Map<String, Object>> antiRotFindings(@RequestHeader("X-Tenant-Id") String tenant) {
        return antiRot.findings(tenant);
    }

    public static class ObservationRequest {
        public String sourceType; public String sourceId; public String traceId; public String runId;
        public String attributionCategory; public String summary; public Map<String, Object> evidence;
    }
    public static class ProposalRequest {
        public String observationId; public String level; public String assetType;
        public String changeClass = "CONTENT"; public String assetKey;
        public String proposedVersion; public String parentAssetId; public String applicability;
        public String riskClass; public String ownerId; public boolean regulated;
    }
    public static class CandidateEvaluationRequest {
        public String trainingSetRef; public String regressionSetRef; public String hiddenHoldoutRef;
        public String suiteId; public String manifestId; public Map<String, Double> metrics;
        public boolean safetyPassed;
    }
    public static class ReviewRequest {
        public String reviewerRole; public String decision; public String comment;
    }
    public static class MetricsRequest { public Map<String, Double> metrics; }
    public static class CanaryRequest {
        public int trafficPercent; public Map<String, Double> metrics;
    }
    public static class PromoteRequest { public boolean automatic; }
    public static class RollbackRequest { public String reason; }
}
