package com.miniclaude.application.governance;

import com.google.gson.Gson;
import com.miniclaude.domain.governance.Evolver;
import com.miniclaude.domain.governance.GovernanceHash;
import com.miniclaude.domain.governance.VersionedAsset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class GovernedEvolutionService {
    private final JdbcTemplate jdbc;
    private final RegistryService registry;
    private final EvaluationService evaluations;
    private final AuditService audit;
    private final Evolver evolver;
    private final Gson gson = new Gson();
    private final Set<String> l3Allowlist;

    public GovernedEvolutionService(JdbcTemplate jdbc, RegistryService registry,
                                    EvaluationService evaluations, AuditService audit, Evolver evolver,
                                    @Value("${governed.evolution.l3-allowlist:}") String allowlist) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.evaluations = evaluations;
        this.audit = audit;
        this.evolver = evolver;
        this.l3Allowlist = Arrays.stream(allowlist.split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
    }

    public Map<String, Object> observe(String tenant, String sourceType, String sourceId,
                                       String traceId, String runId, String attribution,
                                       String summary, Map<String, Object> evidence, String actor) {
        require(summary, "summary");
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        jdbc.update("INSERT INTO experience_observation (id,tenant_id,source_type,source_id,trace_id,"
                        + "run_id,attribution_category,summary,evidence_json,observed_at,observed_by)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id, tenant, sourceType, sourceId, traceId, runId, attribution, summary,
                gson.toJson(evidence == null ? Collections.emptyMap() : evidence),
                Timestamp.from(now), actor);
        audit.append(tenant, "USER", actor, "EXPERIENCE_OBSERVED", "OBSERVATION", id,
                "RECORDED", summary, traceId, runId);
        return observation(id);
    }

    @Transactional
    public Map<String, Object> propose(String tenant, String observationId, String level,
                                       VersionedAsset.Type assetType, String assetKey,
                                       String proposedVersion, String parentAssetId,
                                       String applicability, String riskClass, String ownerId,
                                       boolean regulated, String proposerId) {
        return propose(tenant, observationId, level, assetType,
                assetType == VersionedAsset.Type.RULE ? "RULE" : "CONTENT",
                assetKey, proposedVersion, parentAssetId, applicability, riskClass,
                ownerId, regulated, proposerId);
    }

    @Transactional
    public Map<String, Object> propose(String tenant, String observationId, String level,
                                       VersionedAsset.Type assetType, String changeClass, String assetKey,
                                       String proposedVersion, String parentAssetId,
                                       String applicability, String riskClass, String ownerId,
                                       boolean regulated, String proposerId) {
        validateLevel(level);
        validateChangeClass(changeClass);
        if (regulated && ("L2".equals(level) || "L3".equals(level))) {
            throw new IllegalStateException(
                    "regulated agents are capped at L1; automatic promotion is forbidden");
        }
        if ("L0".equals(level)) {
            throw new IllegalStateException("L0 is observe-only and cannot produce a deployable candidate");
        }
        Map<String, Object> observation = observation(observationId);
        sameTenant(tenant, text(observation, "TENANT_ID"));
        VersionedAsset parent = registry.getById(parentAssetId);
        sameTenant(tenant, parent.getTenantId());
        if (parent.getStatus() != VersionedAsset.Status.PUBLISHED) {
            throw new IllegalStateException("candidate parent must be a stable published asset");
        }
        if (parent.getType() != assetType || !parent.getKey().equals(assetKey)) {
            throw new IllegalArgumentException("candidate target must match its parent asset");
        }
        Evolver.Proposal proposal = evolver.propose(new Evolver.Input(
                text(observation, "SUMMARY"), parent.getContent(), assetType, applicability));
        String patchHash = GovernanceHash.sha256(proposal.getPatch());
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String provenance = gson.toJson(Arrays.asList(
                "observation:" + observationId,
                "source:" + text(observation, "SOURCE_TYPE") + ":" + text(observation, "SOURCE_ID"),
                "parent:" + parent.getId() + "@" + parent.getVersion()));
        jdbc.update("INSERT INTO evolution_candidate (id,tenant_id,observation_id,maturity_level,status,"
                        + "asset_type,change_class,asset_key,proposed_version,parent_asset_id,parent_asset_version,"
                        + "provenance_json,attribution_category,applicability,counterexamples,risk_class,"
                        + "expected_benefit,patch_json,patch_hash,proposer_id,owner_id,regulated,created_at,"
                        + "updated_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, tenant, observationId, level, "PROPOSED", assetType.name(), changeClass, assetKey,
                proposedVersion, parent.getId(), parent.getVersion(), provenance,
                text(observation, "ATTRIBUTION_CATEGORY"), applicability, proposal.getCounterexamples(),
                riskClass, proposal.getExpectedBenefit(), proposal.getPatch(), patchHash, proposerId,
                ownerId, regulated, Timestamp.from(now), Timestamp.from(now));
        audit.append(tenant, "AGENT", proposerId, "EVOLUTION_PROPOSED", "CANDIDATE", id,
                "PROPOSED", patchHash, null, null);
        return candidate(id);
    }

    @Transactional
    public Map<String, Object> evaluate(String candidateId, String evaluatorId, String trainingSetRef,
                                        String regressionSetRef, String hiddenHoldoutRef,
                                        String suiteId, String manifestId, Map<String, Double> metrics,
                                        boolean safetyPassed) {
        Map<String, Object> candidate = requireStatus(candidateId, "PROPOSED");
        if (evaluatorId.equals(text(candidate, "PROPOSER_ID"))) {
            throw new IllegalArgumentException("proposer and evaluator must be different principals");
        }
        require(trainingSetRef, "trainingSetRef");
        require(regressionSetRef, "regressionSetRef");
        require(hiddenHoldoutRef, "hiddenHoldoutRef");
        String tenant = text(candidate, "TENANT_ID");
        Map<String, Object> gate = evaluations.run(tenant, suiteId, manifestId, metrics,
                safetyPassed, evaluatorId);
        String gateId = text(gate, "ID");
        String decision = text(gate, "DECISION");
        String id = UUID.randomUUID().toString();
        String metricsJson = gson.toJson(metrics);
        String resultHash = GovernanceHash.sha256(candidateId + "|" + evaluatorId + "|"
                + trainingSetRef + "|" + regressionSetRef + "|" + hiddenHoldoutRef + "|" + metricsJson);
        jdbc.update("INSERT INTO candidate_evaluation (id,candidate_id,tenant_id,evaluator_id,"
                        + "training_set_ref,regression_set_ref,hidden_holdout_ref,hidden_access_token_hash,"
                        + "suite_id,manifest_id,release_gate_id,metrics_json,result,result_hash,evaluated_at)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, candidateId, tenant, evaluatorId, trainingSetRef, regressionSetRef, hiddenHoldoutRef,
                null, suiteId, manifestId, gateId, metricsJson, decision, resultHash,
                Timestamp.from(Instant.now()));
        transition(candidateId, "PROPOSED", "PASS".equals(decision) ? "EVALUATED" : "REJECTED");
        audit.append(tenant, "USER", evaluatorId, "CANDIDATE_EVALUATED", "CANDIDATE", candidateId,
                decision, resultHash, null, null);
        return candidate(candidateId);
    }

    @Transactional
    public Map<String, Object> review(String candidateId, String reviewerId, String reviewerRole,
                                      String decision, String comment) {
        Map<String, Object> candidate = requireStatus(candidateId, "EVALUATED");
        String level = text(candidate, "MATURITY_LEVEL");
        if ("L2".equals(level)
                && (!"OWNER".equalsIgnoreCase(reviewerRole)
                || !reviewerId.equals(text(candidate, "OWNER_ID")))) {
            throw new IllegalArgumentException("L2 requires approval by the assigned owner");
        }
        if (!"APPROVE".equals(decision) && !"REJECT".equals(decision)) {
            throw new IllegalArgumentException("review decision must be APPROVE or REJECT");
        }
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO candidate_review (id,candidate_id,tenant_id,reviewer_id,reviewer_role,"
                        + "decision,comment_text,reviewed_at) VALUES (?,?,?,?,?,?,?,?)",
                id, candidateId, text(candidate, "TENANT_ID"), reviewerId, reviewerRole, decision,
                comment, Timestamp.from(Instant.now()));
        transition(candidateId, "EVALUATED", "APPROVE".equals(decision) ? "REVIEWED" : "REJECTED");
        audit.append(text(candidate, "TENANT_ID"), "USER", reviewerId, "CANDIDATE_REVIEWED",
                "CANDIDATE", candidateId, decision, comment, null, null);
        return candidate(candidateId);
    }

    public Map<String, Object> shadow(String candidateId, String actor, Map<String, Double> metrics) {
        Map<String, Object> candidate = candidate(candidateId);
        String status = text(candidate, "STATUS");
        if (!"REVIEWED".equals(status) && !canAutoPromote(candidate)) {
            throw new IllegalStateException("candidate must be reviewed before shadow rollout");
        }
        createRollout(candidate, "SHADOW", 0, actor, metrics);
        forceTransition(candidateId, status, "SHADOW");
        return candidate(candidateId);
    }

    public Map<String, Object> canary(String candidateId, int trafficPercent, String actor,
                                      Map<String, Double> metrics) {
        Map<String, Object> candidate = requireStatus(candidateId, "SHADOW");
        if (trafficPercent <= 0 || trafficPercent >= 100) {
            throw new IllegalArgumentException("canary traffic must be between 1 and 99");
        }
        createRollout(candidate, "CANARY", trafficPercent, actor, metrics);
        transition(candidateId, "SHADOW", "CANARY");
        return candidate(candidateId);
    }

    @Transactional
    public Map<String, Object> promote(String candidateId, boolean automatic, String actor) {
        Map<String, Object> candidate = requireStatus(candidateId, "CANARY");
        String level = text(candidate, "MATURITY_LEVEL");
        if ("L0".equals(level)) throw new IllegalStateException("L0 cannot be promoted");
        if ("L2".equals(level) && !hasOwnerApproval(candidateId, text(candidate, "OWNER_ID"))) {
            throw new IllegalStateException("L2 requires owner approval");
        }
        if (automatic && !canAutoPromote(candidate)) {
            throw new IllegalStateException("candidate is outside the L3 automatic promotion boundary");
        }
        if (!automatic && !"L3".equals(level) && !hasApproval(candidateId)) {
            throw new IllegalStateException("manual promotion requires review approval");
        }
        VersionedAsset parent = registry.getById(text(candidate, "PARENT_ASSET_ID"));
        String patch = text(candidate, "PATCH_JSON");
        if (!GovernanceHash.sha256(patch).equals(text(candidate, "PATCH_HASH"))) {
            throw new IllegalStateException("candidate patch hash mismatch");
        }
        String content = parent.getContent() + "\n\n[governed-evolution]\n" + patch;
        VersionedAsset draft = registry.createDraft(text(candidate, "TENANT_ID"),
                VersionedAsset.Type.valueOf(text(candidate, "ASSET_TYPE")),
                text(candidate, "ASSET_KEY"), text(candidate, "PROPOSED_VERSION"),
                parent.getId(), content, null, actor);
        VersionedAsset published = registry.publish(draft.getId(), draft.getContentHash(), actor);
        jdbc.update("UPDATE evolution_candidate SET status='PROMOTED',promoted_asset_id=?,updated_at=?"
                        + " WHERE id=? AND status='CANARY'",
                published.getId(), Timestamp.from(Instant.now()), candidateId);
        createRollout(candidate, "PROMOTE", 100, actor, Collections.emptyMap(), published.getId());
        audit.append(parent.getTenantId(), "USER", actor, "CANDIDATE_PROMOTED", "CANDIDATE",
                candidateId, "PROMOTED", published.getContentHash(), null, null);
        return candidate(candidateId);
    }

    @Transactional
    public Map<String, Object> rollback(String candidateId, String actor, String reason) {
        Map<String, Object> candidate = candidate(candidateId);
        String status = text(candidate, "STATUS");
        if (!Arrays.asList("SHADOW", "CANARY", "PROMOTED").contains(status)) {
            throw new IllegalStateException("only an active rollout can be rolled back");
        }
        String promotedAssetId = text(candidate, "PROMOTED_ASSET_ID");
        if (promotedAssetId != null) registry.revoke(promotedAssetId, actor, reason);
        createRollout(candidate, "ROLLBACK", 0, actor,
                Collections.singletonMap("rollback", 1.0), promotedAssetId);
        forceTransition(candidateId, status, "ROLLED_BACK");
        audit.append(text(candidate, "TENANT_ID"), "USER", actor, "CANDIDATE_ROLLED_BACK",
                "CANDIDATE", candidateId, "ROLLED_BACK", reason, null, null);
        return candidate(candidateId);
    }

    public List<Map<String, Object>> observations(String tenant) {
        return jdbc.queryForList("SELECT * FROM experience_observation WHERE tenant_id=?"
                + " ORDER BY observed_at DESC", tenant);
    }

    public List<Map<String, Object>> candidates(String tenant) {
        return jdbc.queryForList("SELECT * FROM evolution_candidate WHERE tenant_id=?"
                + " ORDER BY created_at DESC", tenant);
    }

    public Map<String, Object> candidate(String id) {
        return jdbc.queryForMap("SELECT * FROM evolution_candidate WHERE id=?", id);
    }

    private Map<String, Object> observation(String id) {
        return jdbc.queryForMap("SELECT * FROM experience_observation WHERE id=?", id);
    }

    private void createRollout(Map<String, Object> candidate, String stage, int traffic, String actor,
                               Map<String, Double> metrics) {
        createRollout(candidate, stage, traffic, actor, metrics, null);
    }

    private void createRollout(Map<String, Object> candidate, String stage, int traffic, String actor,
                               Map<String, Double> metrics, String targetId) {
        jdbc.update("INSERT INTO rollout (id,candidate_id,tenant_id,stage,status,traffic_percent,"
                        + "baseline_asset_id,target_asset_id,metrics_json,started_at,completed_at,actor_id)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), text(candidate, "ID"), text(candidate, "TENANT_ID"),
                stage, "COMPLETED", traffic, text(candidate, "PARENT_ASSET_ID"), targetId,
                gson.toJson(metrics == null ? Collections.emptyMap() : metrics),
                Timestamp.from(Instant.now()), Timestamp.from(Instant.now()), actor);
    }

    private boolean canAutoPromote(Map<String, Object> candidate) {
        String type = text(candidate, "ASSET_TYPE");
        String key = text(candidate, "ASSET_KEY");
        return "L3".equals(text(candidate, "MATURITY_LEVEL"))
                && ("PROMPT".equals(type) || "SKILL".equals(type))
                && "CONTENT".equals(text(candidate, "CHANGE_CLASS"))
                && "LOW".equalsIgnoreCase(text(candidate, "RISK_CLASS"))
                && !Boolean.parseBoolean(text(candidate, "REGULATED"))
                && l3Allowlist.contains(type + ":" + key);
    }

    private boolean hasOwnerApproval(String candidateId, String ownerId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM candidate_review WHERE candidate_id=?"
                        + " AND reviewer_id=? AND reviewer_role='OWNER' AND decision='APPROVE'",
                Integer.class, candidateId, ownerId);
        return count != null && count > 0;
    }

    private boolean hasApproval(String candidateId) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM candidate_review WHERE candidate_id=?"
                + " AND decision='APPROVE'", Integer.class, candidateId);
        return count != null && count > 0;
    }

    private Map<String, Object> requireStatus(String id, String expected) {
        Map<String, Object> candidate = candidate(id);
        if (!expected.equals(text(candidate, "STATUS"))) {
            throw new IllegalStateException("candidate must be in " + expected + " state");
        }
        return candidate;
    }

    private void transition(String id, String from, String to) {
        int updated = jdbc.update("UPDATE evolution_candidate SET status=?,updated_at=?"
                        + " WHERE id=? AND status=?",
                to, Timestamp.from(Instant.now()), id, from);
        if (updated != 1) throw new IllegalStateException("invalid or concurrent candidate transition");
    }

    private void forceTransition(String id, String from, String to) {
        transition(id, from, to);
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) value = row.get(key.toLowerCase());
        return value == null ? null : String.valueOf(value);
    }

    private static void sameTenant(String expected, String actual) {
        if (!expected.equals(actual)) throw new IllegalArgumentException("cross-tenant reference is forbidden");
    }

    private static void validateLevel(String level) {
        if (!new HashSet<>(Arrays.asList("L0", "L1", "L2", "L3")).contains(level)) {
            throw new IllegalArgumentException("maturity level must be L0, L1, L2 or L3");
        }
    }

    private static void validateChangeClass(String changeClass) {
        if (!new HashSet<>(Arrays.asList("CONTENT", "RULE", "PERMISSION", "INVARIANT"))
                .contains(changeClass)) {
            throw new IllegalArgumentException(
                    "change class must be CONTENT, RULE, PERMISSION or INVARIANT");
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
    }
}
