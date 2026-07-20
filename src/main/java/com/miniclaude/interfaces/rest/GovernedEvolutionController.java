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
 * <p><b>职责</b>：观察→提案→评测→复核→shadow→canary→晋升→回滚 状态机的 HTTP 映射。
 * <p><b>上游</b>：治理运营人员；身份来自 {@code X-Tenant-Id}、{@code X-Actor-Id}。
 * <b>下游</b>：{@link GovernedEvolutionService}、{@link AntiRotService}。
 * <p><b>安全/约束</b>：不暴露任意状态跃迁或直接写已发布资产；服务层重验 L0–L3、
 * 职责分离、owner 批准与 L3 allowlist；禁止 L4 自进化。
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

    /** 记录运行经验观察（L0 数据收集，不产生候选）。 */
    @PostMapping("/observations")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> observe(@RequestHeader("X-Tenant-Id") String tenant,
                                       @RequestHeader("X-Actor-Id") String actor,
                                       @RequestBody ObservationRequest body) {
        return evolution.observe(tenant, body.sourceType, body.sourceId, body.traceId, body.runId,
                body.attributionCategory, body.summary, body.evidence, actor);
    }

    /** 列出租户下全部观察记录。 */
    @GetMapping("/observations")
    public List<Map<String, Object>> observations(@RequestHeader("X-Tenant-Id") String tenant) {
        return evolution.observations(tenant);
    }

    /** 基于观察创建进化候选（PROPOSED）。 */
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

    /** 列出进化候选。 */
    @GetMapping("/candidates")
    public List<Map<String, Object>> candidates(@RequestHeader("X-Tenant-Id") String tenant) {
        return evolution.candidates(tenant);
    }

    /**
     * 对候选执行门禁评测（PROPOSED → EVALUATED/REJECTED）。
     *
     * @implNote hidden holdout 仅传隔离引用，API 不接受隐藏样本正文
     */
    @PostMapping("/candidates/{id}/evaluate")
    public Map<String, Object> evaluate(@PathVariable String id,
                                        @RequestHeader("X-Actor-Id") String evaluator,
                                        @RequestBody CandidateEvaluationRequest body) {
        // hidden holdout 仅传递隔离引用；API 不接受隐藏样本正文，避免候选生成方获知测试答案。
        return evolution.evaluate(id, evaluator, body.trainingSetRef, body.regressionSetRef,
                body.hiddenHoldoutRef, body.suiteId, body.manifestId, body.metrics, body.safetyPassed);
    }

    /** 人工复核（EVALUATED → REVIEWED/REJECTED）；L2 须 owner 角色。 */
    @PostMapping("/candidates/{id}/review")
    public Map<String, Object> review(@PathVariable String id,
                                      @RequestHeader("X-Actor-Id") String reviewer,
                                      @RequestBody ReviewRequest body) {
        return evolution.review(id, reviewer, body.reviewerRole, body.decision, body.comment);
    }

    /** 进入 shadow  rollout（0% 流量，对照观测）。 */
    @PostMapping("/candidates/{id}/shadow")
    public Map<String, Object> shadow(@PathVariable String id,
                                      @RequestHeader("X-Actor-Id") String actor,
                                      @RequestBody MetricsRequest body) {
        return evolution.shadow(id, actor, body.metrics);
    }

    /** 进入 canary（1–99% 流量）。 */
    @PostMapping("/candidates/{id}/canary")
    public Map<String, Object> canary(@PathVariable String id,
                                      @RequestHeader("X-Actor-Id") String actor,
                                      @RequestBody CanaryRequest body) {
        return evolution.canary(id, body.trafficPercent, actor, body.metrics);
    }

    /** 晋升候选为已发布资产（CANARY → PROMOTED）。 */
    @PostMapping("/candidates/{id}/promote")
    public Map<String, Object> promote(@PathVariable String id,
                                       @RequestHeader("X-Actor-Id") String actor,
                                       @RequestBody PromoteRequest body) {
        return evolution.promote(id, body.automatic, actor);
    }

    /**
     * 回滚活动 rollout（SHADOW/CANARY/PROMOTED → ROLLED_BACK）。
     *
     * @implNote 撤销晋升版本但保留谱系供取证，不删除候选记录
     */
    @PostMapping("/candidates/{id}/rollback")
    public Map<String, Object> rollback(@PathVariable String id,
                                        @RequestHeader("X-Actor-Id") String actor,
                                        @RequestBody RollbackRequest body) {
        // 回滚撤销晋升版本并保留证据，不删除候选、rollout 或父资产。
        return evolution.rollback(id, actor, body.reason);
    }

    /**
     * 对已发布资产执行 anti-rot 扫描。
     *
     * @implNote 只报告风险，无自动删除/合并能力
     */
    @PostMapping("/anti-rot/scan")
    public List<Map<String, Object>> antiRotScan(@RequestHeader("X-Tenant-Id") String tenant,
                                                 @RequestHeader("X-Actor-Id") String actor,
                                                 @RequestParam(required = false) String currentModel) {
        // 扫描只报告风险；没有自动删除、覆盖或合并生产资产的能力。
        return antiRot.scan(tenant, currentModel, actor);
    }

    /** 查询租户 OPEN anti-rot finding 列表。 */
    @GetMapping("/anti-rot/findings")
    public List<Map<String, Object>> antiRotFindings(@RequestHeader("X-Tenant-Id") String tenant) {
        return antiRot.findings(tenant);
    }

    /** 经验观察 POST 请求体。 */
    public static class ObservationRequest {
        public String sourceType;
        public String sourceId;
        public String traceId;
        public String runId;
        /** 归因类别，如 FAILURE、SUCCESS */
        public String attributionCategory;
        public String summary;
        /** 结构化证据 JSON */
        public Map<String, Object> evidence;
    }

    /** 进化提案 POST 请求体。 */
    public static class ProposalRequest {
        public String observationId;
        /** L1/L2/L3（L0 不可提案） */
        public String level;
        public String assetType;
        /** CONTENT/RULE/PERMISSION/INVARIANT */
        public String changeClass = "CONTENT";
        public String assetKey;
        public String proposedVersion;
        /** 必须为 PUBLISHED 父资产 */
        public String parentAssetId;
        public String applicability;
        /** 风险等级，L3 自动晋升要求 LOW */
        public String riskClass;
        /** L2 必填 owner */
        public String ownerId;
        /** 受监管主体上限 L1 */
        public boolean regulated;
    }

    /** 候选评测 POST 请求体。 */
    public static class CandidateEvaluationRequest {
        public String trainingSetRef;
        public String regressionSetRef;
        /** 隔离 holdout 引用，非正文 */
        public String hiddenHoldoutRef;
        public String suiteId;
        public String manifestId;
        public Map<String, Double> metrics;
        public boolean safetyPassed;
    }

    /** 人工复核 POST 请求体。 */
    public static class ReviewRequest {
        /** L2 须为 OWNER */
        public String reviewerRole;
        /** APPROVE 或 REJECT */
        public String decision;
        public String comment;
    }

    /** shadow/canary 指标上报。 */
    public static class MetricsRequest {
        public Map<String, Double> metrics;
    }

    /** canary 流量与指标。 */
    public static class CanaryRequest {
        /** 1–99 */
        public int trafficPercent;
        public Map<String, Double> metrics;
    }

    /** 晋升请求；automatic 仅 L3 allowlist 内有效。 */
    public static class PromoteRequest {
        public boolean automatic;
    }

    /** 回滚原因（审计用）。 */
    public static class RollbackRequest {
        public String reason;
    }
}
