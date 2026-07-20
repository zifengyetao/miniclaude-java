package com.miniclaude.application.governance;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.miniclaude.domain.governance.GovernanceHash;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Type;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * 执行发布评测并形成 release gate 决策。
 *
 * <p>门禁采用“任一失败即阻断”：安全执行失败拥有独立否决权，同时质量、安全下限以及成本、
 * 延迟上限均须满足。缺少指标不是“未知但可发布”，而是非法输入；这样避免部分评测被误当 PASS。</p>
 */
@Service
public class EvaluationService {
    private static final Type METRICS = new TypeToken<Map<String, Double>>() { }.getType();
    private final JdbcTemplate jdbc;
    private final ReleaseManifestService manifests;
    private final AuditService audit;
    private final MeterRegistry meters;
    private final Gson gson = new Gson();

    public EvaluationService(JdbcTemplate jdbc, ReleaseManifestService manifests,
                             AuditService audit, MeterRegistry meters) {
        this.jdbc = jdbc; this.manifests = manifests; this.audit = audit; this.meters = meters;
    }

    /**
     * 创建评测套件并持久化阈值 JSON。
     *
     * @param thresholds 必须含 quality、safety、cost、latency
     */
    public Map<String, Object> createSuite(String tenant, String key, String version,
                                           Map<String, Double> thresholds) {
        validateMetricSet(thresholds);
        String id = UUID.randomUUID().toString();
        String json = gson.toJson(new TreeMap<>(thresholds));
        jdbc.update("INSERT INTO evaluation_suite (id,tenant_id,suite_key,version,thresholds_json,"
                        + "content_hash,created_at) VALUES (?,?,?,?,?,?,?)",
                id, tenant, key, version, json, GovernanceHash.sha256(json), Timestamp.from(Instant.now()));
        return jdbc.queryForMap("SELECT * FROM evaluation_suite WHERE id=?", id);
    }

    /**
     * 执行评测运行并生成 release gate（PASS/FAIL）。
     *
     * @param safetyPassed 安全执行硬否决信号
     * @return gate 记录 Map，含 DECISION 等字段
     */
    @Transactional
    public Map<String, Object> run(String tenant, String suiteId, String manifestId,
                                   Map<String, Double> metrics, boolean safetyPassed, String actor) {
        validateMetricSet(metrics);
        // 门禁绑定经过完整性校验的精确发布清单，避免评测对象与最终发布对象不一致。
        manifests.verify(manifestId);
        String thresholdJson = jdbc.queryForObject(
                "SELECT thresholds_json FROM evaluation_suite WHERE id=? AND tenant_id=?",
                String.class, suiteId, tenant);
        String metricJson = gson.toJson(new TreeMap<>(metrics));
        String runId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String resultHash = GovernanceHash.sha256(suiteId + "|" + manifestId + "|" + metricJson
                + "|" + safetyPassed);
        jdbc.update("INSERT INTO evaluation_run (id,tenant_id,suite_id,manifest_id,status,metrics_json,"
                        + "safety_passed,result_hash,started_at,completed_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                runId, tenant, suiteId, manifestId, "COMPLETED", metricJson, safetyPassed,
                resultHash, Timestamp.from(now), Timestamp.from(now));

        Map<String, Double> thresholds = gson.fromJson(thresholdJson, METRICS);
        // 保留全部失败原因便于审计；decision 只有在原因集合为空时才为 PASS。
        List<String> reasons = gateReasons(thresholds, metrics, safetyPassed);
        String decision = reasons.isEmpty() ? "PASS" : "FAIL";
        String gateId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO release_gate (id,tenant_id,evaluation_run_id,manifest_id,decision,reasons,"
                        + "decided_at) VALUES (?,?,?,?,?,?,?)",
                gateId, tenant, runId, manifestId, decision, gson.toJson(reasons), Timestamp.from(Instant.now()));
        audit.append(tenant, "USER", actor, "RELEASE_GATE_EVALUATED", "RELEASE", manifestId,
                decision, resultHash, null, runId);
        meters.counter("agentops.release_gate.decisions", "decision", decision).increment();
        return gate(gateId);
    }

    /** @param id release gate 主键 */
    public Map<String, Object> gate(String id) {
        return jdbc.queryForMap("SELECT * FROM release_gate WHERE id=?", id);
    }

    /**
     * 计算 gate 失败原因列表（静态工具，可单测）。
     *
     * @return 空列表表示 PASS
     */
    public static List<String> gateReasons(Map<String, Double> thresholds, Map<String, Double> metrics,
                                           boolean safetyPassed) {
        List<String> reasons = new ArrayList<>();
        // safetyPassed 是硬否决信号，不能被高质量分或低成本抵消。
        if (!safetyPassed) reasons.add("safety execution failed (veto)");
        below(metrics, thresholds, "quality", reasons);
        below(metrics, thresholds, "safety", reasons);
        above(metrics, thresholds, "cost", reasons);
        above(metrics, thresholds, "latency", reasons);
        return reasons;
    }

    private static void below(Map<String, Double> values, Map<String, Double> limits,
                              String name, List<String> reasons) {
        if (values.get(name) < limits.get(name)) reasons.add(name + " below threshold");
    }

    private static void above(Map<String, Double> values, Map<String, Double> limits,
                              String name, List<String> reasons) {
        if (values.get(name) > limits.get(name)) reasons.add(name + " above threshold");
    }

    private static void validateMetricSet(Map<String, Double> values) {
        if (values == null || !values.keySet().containsAll(
                java.util.Arrays.asList("quality", "safety", "cost", "latency"))) {
            throw new IllegalArgumentException("quality, safety, cost and latency metrics are required");
        }
    }
}
