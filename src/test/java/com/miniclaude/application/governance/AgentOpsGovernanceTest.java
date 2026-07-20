package com.miniclaude.application.governance;

import com.miniclaude.domain.governance.AgentReleaseManifest;
import com.miniclaude.domain.governance.PolicyRule;
import com.miniclaude.domain.governance.VersionedAsset;
import com.miniclaude.domain.runtime.PolicyDecision;
import com.miniclaude.infrastructure.governance.DeterministicPolicyEngine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.ActiveProfiles;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * AgentOps 治理不变量的集成测试。
 *
 * <p>这些断言关注失败模式而非普通 CRUD：版本覆盖和动态解析必须失败，DENY/冲突必须关闭，
 * 安全失败必须否决 release gate，审计事件必须连接前序 hash。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:governance-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver"
})
@ActiveProfiles("test")
class AgentOpsGovernanceTest {
    @Autowired RegistryService registry;
    @Autowired ReleaseManifestService manifests;
    @Autowired EvaluationService evaluations;
    @Autowired AuditService audits;

    /** 已发布版本不可被同坐标草稿覆盖；resolve(latest) 与 manifest pin 精确 hash。 */
    @Test
    void publishedVersionsAreImmutableAndManifestPinsExactHash() {
        VersionedAsset draft = registry.createDraft("t1", VersionedAsset.Type.PROMPT,
                "coding", "1.0.0", null, "stable prompt", "sig", "alice");
        VersionedAsset published = registry.publish(draft.getId(), draft.getContentHash(), "alice");

        assertThat(published.getStatus()).isEqualTo(VersionedAsset.Status.PUBLISHED);
        // 数据库唯一坐标证明“同版本改内容”不可行；调用方必须创建显式后继版本。
        assertThatThrownBy(() -> registry.createDraft("t1", VersionedAsset.Type.PROMPT,
                "coding", "1.0.0", null, "changed", null, "mallory"))
                .isInstanceOf(DataIntegrityViolationException.class);
        // 禁止 latest，确保历史运行与回滚始终解析到相同资产。
        assertThatThrownBy(() -> registry.resolve("t1", VersionedAsset.Type.PROMPT,
                "coding", "latest", true)).isInstanceOf(IllegalArgumentException.class);

        AgentReleaseManifest manifest = manifests.create("t1", "coding-agent", "2.0.0",
                java.util.Collections.singletonMap("PROMPT", "coding@1.0.0"), "manifest-sig", "alice");
        assertThat(manifests.verify(manifest.getId()).getAssetPins().get("PROMPT"))
                .contains("coding@1.0.0#").endsWith(published.getContentHash());
        assertThat(manifests.release(manifest.getId(), manifest.getManifestHash(), "alice").getStatus())
                .isEqualTo(AgentReleaseManifest.Status.RELEASED);
    }

    /** DENY 优先于 ALLOW；同优先级 ALLOW 与 REQUIRE_APPROVAL 冲突时 fail-closed 为 DENY。 */
    @Test
    void denyWinsAndConflictsFailClosed() {
        PolicyRule allow = rule("allow", 100, PolicyRule.Effect.ALLOW);
        PolicyRule deny = rule("deny", 1, PolicyRule.Effect.DENY);
        // 即使 DENY 优先级更低也必须胜出，防止高优先级 ALLOW 绕过全局禁令。
        assertThat(DeterministicPolicyEngine.decide(Arrays.asList(allow, deny)).getOutcome())
                .isEqualTo(PolicyDecision.Outcome.DENY);

        PolicyRule approval = rule("approval", 100, PolicyRule.Effect.REQUIRE_APPROVAL);
        // 同优先级效果冲突不猜测配置意图，而是 fail-closed。
        assertThat(DeterministicPolicyEngine.decide(Arrays.asList(allow, approval)).getOutcome())
                .isEqualTo(PolicyDecision.Outcome.DENY);
    }

    /** safetyPassed=false 否决 release gate；审计 append 形成 PREVIOUS_HASH 链。 */
    @Test
    void safetyFailureVetoesReleaseAndAuditIsAppended() {
        Map<String, Double> thresholds = metrics(0.8, 0.9, 10.0, 1000.0);
        List<String> reasons = EvaluationService.gateReasons(
                thresholds, metrics(0.99, 0.99, 1.0, 10.0), false);
        // 即使所有数值指标优秀，独立安全执行失败仍是不可抵消的发布否决。
        assertThat(reasons).contains("safety execution failed (veto)");

        VersionedAsset asset = registry.createDraft("t-gate", VersionedAsset.Type.VERIFIER,
                "safety-check", "1.0", null, "verify", null, "alice");
        registry.publish(asset.getId(), asset.getContentHash(), "alice");
        AgentReleaseManifest manifest = manifests.create("t-gate", "agent", "1.0",
                java.util.Collections.singletonMap("VERIFIER", "safety-check@1.0"),
                null, "alice");
        Map<String, Object> suite = evaluations.createSuite("t-gate", "release", "1.0", thresholds);
        String suiteId = String.valueOf(suite.get(suite.containsKey("ID") ? "ID" : "id"));
        Map<String, Object> gate = evaluations.run("t-gate", suiteId, manifest.getId(),
                metrics(0.99, 0.99, 1.0, 10.0), false, "alice");
        assertThat(gate).containsValue("FAIL");

        audits.append("t-audit", "USER", "alice", "TOOL_EXECUTED", "TOOL", "shell",
                "ALLOW", "sensitive body is hashed", "trace-1", "run-1");
        audits.append("t-audit", "AGENT", "agent-1", "RUN_COMPLETED", "RUN", "run-1",
                "SUCCESS", "result", "trace-1", "run-1");
        List<Map<String, Object>> events = audits.query("t-audit", null, null);
        assertThat(events).hasSize(2);
        assertThat(events.get(0)).containsKeys("EVENT_HASH", "PREVIOUS_HASH", "PAYLOAD_HASH");
        // 最新事件必须引用前一事件；否则删除或改写中间事件无法沿链暴露。
        assertThat(events.get(0).get("PREVIOUS_HASH")).isNotNull();
    }

    private static PolicyRule rule(String key, int priority, PolicyRule.Effect effect) {
        return new PolicyRule(key, "t1", key, "1", "*", "*", "*", priority, effect);
    }

    private static Map<String, Double> metrics(double quality, double safety, double cost, double latency) {
        Map<String, Double> result = new HashMap<>();
        result.put("quality", quality);
        result.put("safety", safety);
        result.put("cost", cost);
        result.put("latency", latency);
        return result;
    }
}
