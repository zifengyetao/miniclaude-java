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

    @PostMapping("/assets")
    @ResponseStatus(HttpStatus.CREATED)
    public VersionedAsset createAsset(@RequestHeader("X-Tenant-Id") String tenant,
                                      @RequestHeader("X-Actor-Id") String actor,
                                      @RequestBody AssetRequest body) {
        return registry.createDraft(tenant, VersionedAsset.Type.valueOf(body.type.toUpperCase()),
                body.key, body.version, body.parentId, body.content, body.signature, actor);
    }

    @GetMapping("/assets")
    public List<VersionedAsset> assets(@RequestHeader("X-Tenant-Id") String tenant) {
        return registry.list(tenant);
    }

    @GetMapping("/assets/resolve")
    public VersionedAsset resolve(@RequestHeader("X-Tenant-Id") String tenant,
                                  @RequestParam VersionedAsset.Type type, @RequestParam String key,
                                  @RequestParam String version,
                                  @RequestParam(defaultValue = "false") boolean forRun) {
        return registry.resolve(tenant, type, key, version, forRun);
    }

    @PutMapping("/assets/{id}/publish")
    public VersionedAsset publish(@PathVariable String id, @RequestHeader("X-Actor-Id") String actor,
                                  @RequestBody HashRequest body) {
        return registry.publish(id, body.hash, actor);
    }

    @PutMapping("/assets/{id}/deprecate")
    public VersionedAsset deprecate(@PathVariable String id, @RequestHeader("X-Actor-Id") String actor) {
        return registry.deprecate(id, actor);
    }

    @PostMapping("/manifests")
    @ResponseStatus(HttpStatus.CREATED)
    public AgentReleaseManifest manifest(@RequestHeader("X-Tenant-Id") String tenant,
                                         @RequestHeader("X-Actor-Id") String actor,
                                         @RequestBody ManifestRequest body) {
        return manifests.create(tenant, body.agentKey, body.version, body.assetPins, body.signature, actor);
    }

    @GetMapping("/manifests/{id}/verify")
    public AgentReleaseManifest verifyManifest(@PathVariable String id) {
        return manifests.verify(id);
    }

    @PutMapping("/manifests/{id}/release")
    public AgentReleaseManifest releaseManifest(@PathVariable String id,
                                                @RequestHeader("X-Actor-Id") String actor,
                                                @RequestBody HashRequest body) {
        return manifests.release(id, body.hash, actor);
    }

    @PostMapping("/policies/rules")
    @ResponseStatus(HttpStatus.CREATED)
    public PolicyRule rule(@RequestHeader("X-Tenant-Id") String tenant, @RequestBody RuleRequest body) {
        return policies.addRule(tenant, body.key, body.version, body.scope, body.actionPattern,
                body.resourcePattern, body.priority, PolicyRule.Effect.valueOf(body.effect.toUpperCase()));
    }

    @PostMapping("/policies/evaluate")
    public PolicyDecision evaluate(@RequestHeader("X-Tenant-Id") String tenant,
                                   @RequestHeader(value = "X-Trace-Id", defaultValue = "rest") String trace,
                                   @RequestHeader(value = "X-Run-Id", defaultValue = "policy-evaluation") String run,
                                   @RequestHeader(value = "X-Actor-Id", defaultValue = "anonymous") String actor,
                                   @RequestBody PolicyEvaluationRequest body) {
        ExecutionContext context = new ExecutionContext(Paths.get("."), tenant, actor, run, trace);
        return policies.evaluate(new PolicyRequest(context, body.action, body.resource));
    }

    @PostMapping("/evaluations/suites")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> suite(@RequestHeader("X-Tenant-Id") String tenant,
                                     @RequestBody SuiteRequest body) {
        return evaluations.createSuite(tenant, body.key, body.version, body.thresholds);
    }

    @PostMapping("/evaluations/runs")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, Object> evaluationRun(@RequestHeader("X-Tenant-Id") String tenant,
                                             @RequestHeader("X-Actor-Id") String actor,
                                             @RequestBody EvaluationRequest body) {
        return evaluations.run(tenant, body.suiteId, body.manifestId, body.metrics,
                body.safetyPassed, actor);
    }

    @GetMapping("/release-gates/{id}")
    public Map<String, Object> gate(@PathVariable String id) {
        return evaluations.gate(id);
    }

    @GetMapping("/audits")
    public List<Map<String, Object>> audits(@RequestHeader("X-Tenant-Id") String tenant,
                                            @RequestParam(required = false) String resourceType,
                                            @RequestParam(required = false) String resourceId) {
        return audits.query(tenant, resourceType, resourceId);
    }

    public static class AssetRequest {
        public String type; public String key; public String version; public String parentId;
        public String content; public String signature;
    }
    public static class HashRequest { public String hash; }
    public static class ManifestRequest {
        public String agentKey; public String version; public Map<String, String> assetPins; public String signature;
    }
    public static class RuleRequest {
        public String key; public String version; public String scope = "*"; public String actionPattern = "*";
        public String resourcePattern = "*"; public int priority; public String effect;
    }
    public static class PolicyEvaluationRequest { public String action; public String resource; }
    public static class SuiteRequest {
        public String key; public String version; public Map<String, Double> thresholds;
    }
    public static class EvaluationRequest {
        public String suiteId; public String manifestId; public Map<String, Double> metrics;
        public boolean safetyPassed;
    }
}
