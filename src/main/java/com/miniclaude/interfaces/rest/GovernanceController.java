package com.miniclaude.interfaces.rest;

import com.miniclaude.application.governance.AuditService;
import com.miniclaude.application.governance.EvaluationService;
import com.miniclaude.application.governance.RegistryService;
import com.miniclaude.application.governance.ReleaseManifestService;
import com.miniclaude.domain.governance.AgentReleaseManifest;
import com.miniclaude.domain.governance.PolicyRule;
import com.miniclaude.domain.governance.VersionedAsset;
import com.miniclaude.domain.runtime.ExecutionContext;
import com.miniclaude.domain.runtime.PolicyDecision;
import com.miniclaude.domain.runtime.PolicyRequest;
import com.miniclaude.infrastructure.governance.DeterministicPolicyEngine;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * AgentOps 治理 HTTP 边界。
 *
 * <p><b>职责</b>：资产注册、发布清单、策略规则、评测门禁与审计查询的协议映射。
 * <p><b>上游</b>：治理控制台；租户与操作者来自 {@code X-Tenant-Id}、{@code X-Actor-Id} 请求头。
 * <b>下游</b>：{@link RegistryService}、{@link ReleaseManifestService}、
 * {@link DeterministicPolicyEngine}、{@link EvaluationService}、{@link AuditService}。
 * <p><b>安全/约束</b>：deny-first、精确版本 pin、hash 校验均由服务层执行；REST 不提供
 * latest 便利后门；调用方不能通过请求体覆盖头中的租户/操作者身份。
 */
@RestController
@RequestMapping("/api/v1/governance")
public class GovernanceController {
    private final RegistryService registry;
    private final ReleaseManifestService manifests;
    private final DeterministicPolicyEngine policies;
    private final EvaluationService evaluations;
    private final AuditService audits;

    public GovernanceController(RegistryService registry, ReleaseManifestService manifests,
                                DeterministicPolicyEngine policies, EvaluationService evaluations,
                                AuditService audits) {
        this.registry = registry; this.manifests = manifests; this.policies = policies;
        this.evaluations = evaluations; this.audits = audits;
    }

    /**
     * 创建版本化资产草稿。
     *
     * @param tenant 租户（必填头）
     * @param actor  操作者 ID（必填头，写入审计）
     * @param body   资产类型、键、版本、内容与可选签名
     */
    @PostMapping("/assets")
    @ResponseStatus(HttpStatus.CREATED)
    public VersionedAsset createAsset(@RequestHeader("X-Tenant-Id") String tenant,
                                      @RequestHeader("X-Actor-Id") String actor,
                                      @RequestBody AssetRequest body) {
        return registry.createDraft(tenant, VersionedAsset.Type.valueOf(body.type.toUpperCase()),
                body.key, body.version, body.parentId, body.content, body.signature, actor);
    }

    /** 列出租户下全部资产（含各状态）。 */
    @GetMapping("/assets")
    public List<VersionedAsset> assets(@RequestHeader("X-Tenant-Id") String tenant) {
        return registry.list(tenant);
    }

    /**
     * 按精确版本解析已发布资产。
     *
     * @param forRun {@code true} 表示运行期解析，仍禁止 latest/通配符
     * @implNote 服务层强制精确版本并复算内容 hash；REST 层不提供 latest 的便利后门
     */
    @GetMapping("/assets/resolve")
    public VersionedAsset resolve(@RequestHeader("X-Tenant-Id") String tenant,
                                  @RequestParam VersionedAsset.Type type, @RequestParam String key,
                                  @RequestParam String version,
                                  @RequestParam(defaultValue = "false") boolean forRun) {
        // 服务层强制精确版本并复算内容 hash；REST 层不提供 latest 的便利后门。
        return registry.resolve(tenant, type, key, version, forRun);
    }

    /**
     * 将草稿资产发布为 PUBLISHED。
     *
     * @param body.hash 客户端审批时看到的内容 hash，不一致则拒绝发布
     */
    @PutMapping("/assets/{id}/publish")
    public VersionedAsset publish(@PathVariable String id, @RequestHeader("X-Actor-Id") String actor,
                                  @RequestBody HashRequest body) {
        // 客户端提交其审批时看到的 hash；内容若在审批后变化，发布将失败而不是静默接受。
        return registry.publish(id, body.hash, actor);
    }

    /** 弃用已发布资产（DEPRECATED，非删除）。 */
    @PutMapping("/assets/{id}/deprecate")
    public VersionedAsset deprecate(@PathVariable String id, @RequestHeader("X-Actor-Id") String actor) {
        return registry.deprecate(id, actor);
    }

    /** 创建 Agent 发布清单草稿（资产 pin 转为 key@version#hash）。 */
    @PostMapping("/manifests")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentReleaseManifest manifest(@RequestHeader("X-Tenant-Id") String tenant,
                                         @RequestHeader("X-Actor-Id") String actor,
                                         @RequestBody ManifestRequest body) {
        return manifests.create(tenant, body.agentKey, body.version, body.assetPins, body.signature, actor);
    }

    /** 验证清单 hash 与各 pin 资产完整性。 */
    @GetMapping("/manifests/{id}/verify")
    public AgentReleaseManifest verifyManifest(@PathVariable String id) {
        return manifests.verify(id);
    }

    /** 将 DRAFT 清单发布为 RELEASED（需 hash 乐观锁）。 */
    @PutMapping("/manifests/{id}/release")
    public AgentReleaseManifest releaseManifest(@PathVariable String id,
                                                @RequestHeader("X-Actor-Id") String actor,
                                                @RequestBody HashRequest body) {
        return manifests.release(id, body.hash, actor);
    }

    /** 添加确定性策略规则。 */
    @PostMapping("/policies/rules")
    @ResponseStatus(HttpStatus.CREATED)
    public PolicyRule rule(@RequestHeader("X-Tenant-Id") String tenant, @RequestBody RuleRequest body) {
        return policies.addRule(tenant, body.key, body.version, body.scope, body.actionPattern,
                body.resourcePattern, body.priority, PolicyRule.Effect.valueOf(body.effect.toUpperCase()));
    }

    /**
     * 在线评估 action/resource 是否被策略允许。
     *
     * @implNote 将 REST 身份和追踪字段封装进 {@link ExecutionContext}，确保决策可关联审计
     */
    @PostMapping("/policies/evaluate")
    public PolicyDecision evaluate(@RequestHeader("X-Tenant-Id") String tenant,
                                   @RequestHeader(value = "X-Trace-Id", defaultValue = "rest") String trace,
                                   @RequestHeader(value = "X-Run-Id", defaultValue = "policy-evaluation") String run,
                                   @RequestHeader(value = "X-Actor-Id", defaultValue = "anonymous") String actor,
                                   @RequestBody PolicyEvaluationRequest body) {
        // 将 REST 身份和追踪字段封装进策略上下文，确保 deny/allow 决策可关联到审计事件。
        ExecutionContext context = new ExecutionContext(Paths.get("."), tenant, actor, run, trace);
        return policies.evaluate(new PolicyRequest(context, body.action, body.resource));
    }

    /** 创建评测套件（含 quality/safety/cost/latency 阈值）。 */
    @PostMapping("/evaluations/suites")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> suite(@RequestHeader("X-Tenant-Id") String tenant,
                                     @RequestBody SuiteRequest body) {
        return evaluations.createSuite(tenant, body.key, body.version, body.thresholds);
    }

    /** 执行评测运行并生成 release gate 决策。 */
    @PostMapping("/evaluations/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> evaluationRun(@RequestHeader("X-Tenant-Id") String tenant,
                                             @RequestHeader("X-Actor-Id") String actor,
                                             @RequestBody EvaluationRequest body) {
        return evaluations.run(tenant, body.suiteId, body.manifestId, body.metrics,
                body.safetyPassed, actor);
    }

    /** 查询 release gate 详情。 */
    @GetMapping("/release-gates/{id}")
    public Map<String, Object> gate(@PathVariable String id) {
        return evaluations.gate(id);
    }

    /** 按租户（及可选资源过滤）查询审计事件。 */
    @GetMapping("/audits")
    public List<Map<String, Object>> audits(@RequestHeader("X-Tenant-Id") String tenant,
                                            @RequestParam(required = false) String resourceType,
                                            @RequestParam(required = false) String resourceId) {
        return audits.query(tenant, resourceType, resourceId);
    }

    /** 创建资产草稿的请求体（公开字段供 JSON 反序列化）。 */
    public static class AssetRequest {
        /** 资产类型，如 PROMPT、SKILL、RULE */
        public String type;
        /** 租户内唯一键 */
        public String key;
        /** 精确版本号，禁止 latest */
        public String version;
        /** 可选父资产 ID（后继修订） */
        public String parentId;
        /** 资产正文 */
        public String content;
        /** 可选签名 */
        public String signature;
    }

    /** 发布/清单发布时的内容 hash 确认令牌。 */
    public static class HashRequest {
        /** 审批者确认的 SHA-256 摘要 */
        public String hash;
    }

    /** 创建发布清单的请求体。 */
    public static class ManifestRequest {
        /** Agent 逻辑键 */
        public String agentKey;
        /** 清单版本 */
        public String version;
        /** 资产类型 → key@version 坐标 */
        public Map<String, String> assetPins;
        /** 可选清单签名 */
        public String signature;
    }

    /** 添加策略规则的请求体。 */
    public static class RuleRequest {
        public String key;
        public String version;
        /** 规则作用域，默认 {@code *} */
        public String scope = "*";
        /** 动作模式，默认 {@code *} */
        public String actionPattern = "*";
        /** 资源模式，默认 {@code *} */
        public String resourcePattern = "*";
        /** 优先级，数值越大越优先 */
        public int priority;
        /** ALLOW 或 DENY */
        public String effect;
    }

    /** 策略在线评估请求。 */
    public static class PolicyEvaluationRequest {
        /** 待评估动作，如 tool:execute */
        public String action;
        /** 待评估资源标识 */
        public String resource;
    }

    /** 创建评测套件请求。 */
    public static class SuiteRequest {
        public String key;
        public String version;
        /** 必须含 quality、safety、cost、latency 四门阈值 */
        public Map<String, Double> thresholds;
    }

    /** 触发评测运行请求。 */
    public static class EvaluationRequest {
        public String suiteId;
        public String manifestId;
        /** 实测指标，须与套件阈值键一致 */
        public Map<String, Double> metrics;
        /** 安全执行是否通过（硬否决） */
        public boolean safetyPassed;
    }
}
