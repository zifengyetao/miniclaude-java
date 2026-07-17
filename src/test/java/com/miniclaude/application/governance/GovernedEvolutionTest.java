package com.miniclaude.application.governance;

import com.miniclaude.domain.governance.AgentReleaseManifest;
import com.miniclaude.domain.governance.VersionedAsset;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 受控进化安全边界的集成测试。
 *
 * <p>覆盖候选不能自改生产、L2 owner 责任、L3 自动晋升白名单、hidden holdout 隔离、
 * rollback 保留稳定父版本，以及 anti-rot 只报告不删除。测试刻意验证越权路径失败，
 * 防止未来新增“便捷入口”时绕过候选状态机。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:evolution-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "governed.evolution.l3-allowlist=PROMPT:auto-prompt",
        "governed.evolution.anti-rot.stale-days=0",
        "governed.evolution.anti-rot.prompt-max-chars=20"
})
@ActiveProfiles("test")
class GovernedEvolutionTest {
    @Autowired GovernedEvolutionService evolution;
    @Autowired AntiRotService antiRot;
    @Autowired RegistryService registry;
    @Autowired EvaluationService evaluations;
    @Autowired ReleaseManifestService manifests;
    @Autowired JdbcTemplate jdbc;

    @Test
    void proposerCannotSelfModifyProduction() {
        Fixture f = fixture("no-self-modify", VersionedAsset.Type.PROMPT, "stable", "base");
        int before = registry.list(f.tenant).size();

        Map<String, Object> candidate = propose(f, "L1", "LOW", null, false, "proposer");

        // 提案只新增候选记录；Evolver 无 Registry 写能力，稳定资产数量和内容均保持不变。
        assertThat(registry.list(f.tenant)).hasSize(before);
        assertThat(registry.getById(f.asset.getId()).getContent()).isEqualTo("base");
        assertThat(text(candidate, "STATUS")).isEqualTo("PROPOSED");
        assertThat(text(candidate, "PATCH_HASH")).isNotBlank();
        assertThat(text(candidate, "PARENT_ASSET_VERSION")).isEqualTo(f.asset.getVersion());
    }

    @Test
    void l2RequiresAssignedOwnerApproval() {
        Fixture f = fixture("l2-owner", VersionedAsset.Type.PROMPT, "owned", "base");
        Map<String, Object> candidate = evaluated(f, "L2", "LOW", "owner-a", false);
        String id = text(candidate, "ID");

        // 普通 reviewer 和错误 owner 均不能代替候选绑定的责任人批准 L2。
        assertThatThrownBy(() -> evolution.review(id, "reviewer", "REVIEWER", "APPROVE", "ok"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("owner");
        assertThatThrownBy(() -> evolution.review(id, "owner-b", "OWNER", "APPROVE", "ok"))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(text(evolution.review(id, "owner-a", "OWNER", "APPROVE", "approved"), "STATUS"))
                .isEqualTo("REVIEWED");
    }

    @Test
    void l3AutoPromotionIsRestrictedToAllowlistedLowRiskPromptOrSkill() {
        Fixture allowed = fixture("l3-allowed", VersionedAsset.Type.PROMPT, "auto-prompt", "base");
        Map<String, Object> allowedCandidate = evaluated(allowed, "L3", "LOW", null, false);
        String allowedId = text(allowedCandidate, "ID");
        evolution.shadow(allowedId, "automation", Collections.emptyMap());
        evolution.canary(allowedId, 5, "automation", Collections.emptyMap());
        // 只有 allowlist 内的低风险 CONTENT 类 PROMPT 才走完整 L3 自动路径。
        assertThat(text(evolution.promote(allowedId, true, "automation"), "STATUS"))
                .isEqualTo("PROMOTED");

        Fixture forbidden = fixture("l3-rule", VersionedAsset.Type.RULE, "auto-rule", "rule");
        Map<String, Object> forbiddenCandidate = evaluated(forbidden, "L3", "LOW", null, false);
        String forbiddenId = text(forbiddenCandidate, "ID");
        evolution.review(forbiddenId, "reviewer", "REVIEWER", "APPROVE", "manual only");
        evolution.shadow(forbiddenId, "reviewer", Collections.emptyMap());
        evolution.canary(forbiddenId, 5, "reviewer", Collections.emptyMap());
        // RULE 即便低风险且已人工复核，也不因此获得自动晋升权限。
        assertThatThrownBy(() -> evolution.promote(forbiddenId, true, "automation"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("automatic promotion boundary");

        Fixture permission = fixture("l3-permission", VersionedAsset.Type.PROMPT,
                "auto-prompt", "base");
        Map<String, Object> observation = evolution.observe(permission.tenant, "RUN", "run-1",
                "trace-1", "run-1", "POLICY_GAP", "permission change",
                Collections.emptyMap(), "observer");
        Map<String, Object> permissionCandidate = evolution.propose(permission.tenant,
                text(observation, "ID"), "L3", VersionedAsset.Type.PROMPT, "PERMISSION",
                "auto-prompt", "2.0", permission.asset.getId(), "matching requests",
                "LOW", null, false, "proposer");
        String permissionId = text(evolution.evaluate(text(permissionCandidate, "ID"), "evaluator",
                "train://1", "regression://1", "vault://holdout/1", permission.suiteId,
                permission.manifest.getId(), goodMetrics(), true), "ID");
        evolution.review(permissionId, "reviewer", "REVIEWER", "APPROVE", "manual only");
        evolution.shadow(permissionId, "reviewer", Collections.emptyMap());
        evolution.canary(permissionId, 5, "reviewer", Collections.emptyMap());
        // PERMISSION 变更被 changeClass 边界拦截，不能伪装成 allowlist 内的提示内容更新。
        assertThatThrownBy(() -> evolution.promote(permissionId, true, "automation"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void proposerAndEvaluatorAreSeparatedAndHiddenContentIsIsolated() {
        Fixture f = fixture("hidden", VersionedAsset.Type.PROMPT, "hidden-safe", "base");
        Map<String, Object> candidate = propose(f, "L1", "LOW", null, false, "proposer");
        String id = text(candidate, "ID");

        // 提案者不能兼任评测者，降低自评放行与选择性指标报告风险。
        assertThatThrownBy(() -> evolution.evaluate(id, "proposer", "train://1", "regression://1",
                "vault://holdout/1", f.suiteId, f.manifest.getId(), goodMetrics(), true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("different principals");

        Map<String, Object> evaluated = evolution.evaluate(id, "independent-evaluator", "train://1",
                "regression://1", "vault://holdout/1", f.suiteId, f.manifest.getId(),
                goodMetrics(), true);
        // 候选 patch 不得含 holdout 引用或秘密；否则生成器可能针对隐藏样本过拟合。
        assertThat(text(evaluated, "PATCH_JSON")).doesNotContain("vault://", "hidden-secret");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM candidate_evaluation"
                + " WHERE candidate_id=?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    void rollbackRevokesPromotedVersionAndRestoresStableParent() {
        Fixture f = fixture("rollback", VersionedAsset.Type.PROMPT, "auto-prompt", "base");
        String id = text(evaluated(f, "L3", "LOW", null, false), "ID");
        evolution.shadow(id, "automation", Collections.emptyMap());
        evolution.canary(id, 10, "automation", Collections.emptyMap());
        Map<String, Object> promoted = evolution.promote(id, true, "automation");
        String promotedAssetId = text(promoted, "PROMOTED_ASSET_ID");

        assertThat(text(evolution.rollback(id, "operator", "canary regression"), "STATUS"))
                .isEqualTo("ROLLED_BACK");
        // 回滚撤销新版本但不删除它，且父版本一直保持 PUBLISHED，可复现恢复并保留取证链。
        assertThat(registry.getById(promotedAssetId).getStatus())
                .isEqualTo(VersionedAsset.Status.REVOKED);
        assertThat(registry.getById(f.asset.getId()).getStatus())
                .isEqualTo(VersionedAsset.Status.PUBLISHED);
    }

    @Test
    void antiRotOnlyCreatesFindingsAndNeverDeletesAssets() {
        String tenant = "anti-rot";
        VersionedAsset first = publish(tenant, VersionedAsset.Type.PROMPT, "one", "1.0",
                "[models:old-model] duplicated prompt content");
        VersionedAsset second = publish(tenant, VersionedAsset.Type.PROMPT, "two", "1.0",
                "[models:old-model] duplicated prompt content");

        List<Map<String, Object>> findings = antiRot.scan(tenant, "new-model", "operator");

        assertThat(findings).extracting(row -> text(row, "FINDING_TYPE"))
                .contains("DUPLICATE", "PROMPT_BLOAT", "MODEL_INCOMPATIBLE");
        // 启发式扫描只能产生 finding；即使同时命中多个风险，也不能自动改写或删除资产。
        assertThat(registry.getById(first.getId()).getStatus()).isEqualTo(VersionedAsset.Status.PUBLISHED);
        assertThat(registry.getById(second.getId()).getStatus()).isEqualTo(VersionedAsset.Status.PUBLISHED);
    }

    private Map<String, Object> evaluated(Fixture f, String level, String risk,
                                          String owner, boolean regulated) {
        Map<String, Object> candidate = propose(f, level, risk, owner, regulated, "proposer");
        return evolution.evaluate(text(candidate, "ID"), "evaluator", "train://1", "regression://1",
                "vault://holdout/1", f.suiteId, f.manifest.getId(), goodMetrics(), true);
    }

    private Map<String, Object> propose(Fixture f, String level, String risk,
                                        String owner, boolean regulated, String proposer) {
        Map<String, Object> observation = evolution.observe(f.tenant, "RUN", "run-1", "trace-1",
                "run-1", "QUALITY_GAP", "improve " + f.tenant, Collections.emptyMap(), "observer");
        return evolution.propose(f.tenant, text(observation, "ID"), level, f.asset.getType(),
                f.asset.getKey(), "2.0", f.asset.getId(), "matching requests", risk,
                owner, regulated, proposer);
    }

    private Fixture fixture(String tenant, VersionedAsset.Type type, String key, String content) {
        VersionedAsset asset = publish(tenant, type, key, "1.0", content);
        AgentReleaseManifest manifest = manifests.create(tenant, "agent", "1.0",
                Collections.singletonMap(type.name(), key + "@1.0"), null, "owner");
        Map<String, Object> suite = evaluations.createSuite(tenant, "governed", "1.0",
                thresholds());
        return new Fixture(tenant, asset, manifest, text(suite, "ID"));
    }

    private VersionedAsset publish(String tenant, VersionedAsset.Type type, String key,
                                   String version, String content) {
        VersionedAsset draft = registry.createDraft(tenant, type, key, version, null,
                content, null, "owner");
        return registry.publish(draft.getId(), draft.getContentHash(), "owner");
    }

    private static Map<String, Double> thresholds() {
        Map<String, Double> result = new HashMap<>();
        result.put("quality", 0.8);
        result.put("safety", 0.9);
        result.put("cost", 10.0);
        result.put("latency", 1000.0);
        return result;
    }

    private static Map<String, Double> goodMetrics() {
        Map<String, Double> result = new HashMap<>();
        result.put("quality", 0.95);
        result.put("safety", 0.99);
        result.put("cost", 1.0);
        result.put("latency", 20.0);
        return result;
    }

    private static String text(Map<String, Object> row, String key) {
        Object value = row.get(key);
        if (value == null) value = row.get(key.toLowerCase());
        return value == null ? null : String.valueOf(value);
    }

    private static final class Fixture {
        private final String tenant;
        private final VersionedAsset asset;
        private final AgentReleaseManifest manifest;
        private final String suiteId;

        private Fixture(String tenant, VersionedAsset asset, AgentReleaseManifest manifest,
                        String suiteId) {
            this.tenant = tenant;
            this.asset = asset;
            this.manifest = manifest;
            this.suiteId = suiteId;
        }
    }
}
