package com.miniclaude.infrastructure.scenario;

import com.miniclaude.domain.scenario.ScenarioArtifact;
import com.miniclaude.infrastructure.durable.JdbcDurableStore;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
/**
 * 场景制品（Artifact）的 JDBC 持久化适配器。
 *
 * <p><b>制品是什么</b>：场景 Run 执行过程中产出的结构化输出——如 Coding 补丁、
 * 分析报告、风控 REVIEW 建议、交易 OMS 草稿等。它们进入 {@code scenario_artifact}
 * 表供工作台展示与审批绑定。</p>
 *
 * <p><b>为何计算 content_hash</b>：审批流程必须证明「人工批准的是同一份提案内容」。
 * 保存时对规范化正文做 SHA-256（复用 {@link JdbcDurableStore#sha256}），
 * 后续 {@code ApprovalRequest} 可比对 action_hash，防止「先审 A、后执行 B」。</p>
 *
 * <p><b>租户隔离</b>：查询强制 {@code tenant_id + run_id} 双条件，避免仅凭 runId
 * UUID 猜测跨租户读取敏感案例包（runId 在日志中可能泄露）。</p>
 */
public class JdbcScenarioArtifactRepository implements ScenarioArtifact.Repository {
    /** Spring JDBC 模板，连接 Flyway 迁移后的 {@code scenario_artifact} 表 */
    private final JdbcTemplate jdbc;

    /**
     * @param jdbc 由 Spring Boot 自动配置的 {@link JdbcTemplate}
     */
    public JdbcScenarioArtifactRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    /**
     * 持久化一条新制品并返回带 id/hash 的完整实体。
     *
     * <p>普通 save 每次生成新 UUID；Graph/Tool 重试应使用 saveIdempotent，
     * 由数据库唯一键保证同一逻辑制品只保存一次。</p>
     *
     * @param tenantId 租户标识，写入行级隔离字段
     * @param runId    所属 Durable Run
     * @param type     制品类型（如 PATCH、REPORT、DRAFT_ORDER）
     * @param name     人类可读名称
     * @param content  正文；{@code null} 规范化为空串再哈希，保证 hash 与库内一致
     */
    @Override
    public ScenarioArtifact save(String tenantId, String runId, String type, String name, String content) {
        return insert(tenantId, runId, type, name, content, null);
    }

    @Override
    public ScenarioArtifact saveIdempotent(String tenantId, String runId, String type, String name,
                                           String content, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.trim().isEmpty()
                || idempotencyKey.length() > 240) {
            throw new IllegalArgumentException("invalid artifact idempotency key");
        }
        ScenarioArtifact existing = findByKey(tenantId, runId, idempotencyKey);
        if (existing != null) return samePayload(existing, type, name, content);
        try {
            return insert(tenantId, runId, type, name, content, idempotencyKey);
        } catch (DuplicateKeyException concurrentReplay) {
            ScenarioArtifact replayed = findByKey(tenantId, runId, idempotencyKey);
            if (replayed == null) throw concurrentReplay;
            return samePayload(replayed, type, name, content);
        }
    }

    private ScenarioArtifact insert(String tenantId, String runId, String type, String name,
                                    String content, String idempotencyKey) {
        String normalized = content == null ? "" : content;
        ScenarioArtifact artifact = new ScenarioArtifact(UUID.randomUUID().toString(), tenantId,
                runId, type, name, normalized, JdbcDurableStore.sha256(normalized), Instant.now());
        jdbc.update("INSERT INTO scenario_artifact(id,tenant_id,run_id,artifact_type,name,content,"
                        + "content_hash,created_at,idempotency_key) VALUES (?,?,?,?,?,?,?,?,?)",
                artifact.getId(), tenantId, runId, type, name, normalized, artifact.getContentHash(),
                Timestamp.from(artifact.getCreatedAt()), idempotencyKey);
        return artifact;
    }

    private ScenarioArtifact findByKey(String tenantId, String runId, String idempotencyKey) {
        List<ScenarioArtifact> found = jdbc.query(
                "SELECT * FROM scenario_artifact WHERE tenant_id=? AND run_id=? AND idempotency_key=?",
                (rs, n) -> artifact(rs), tenantId, runId, idempotencyKey);
        return found.isEmpty() ? null : found.get(0);
    }

    private ScenarioArtifact samePayload(ScenarioArtifact existing, String type, String name,
                                         String content) {
        String normalized = content == null ? "" : content;
        String hash = JdbcDurableStore.sha256(normalized);
        if (!existing.getType().equals(type) || !existing.getName().equals(name)
                || !existing.getContentHash().equals(hash)) {
            throw new IllegalStateException("artifact idempotency key reused with different payload");
        }
        return existing;
    }

    /**
     * 列出某 Run 下全部制品，按创建时间升序（时间线顺序）。
     *
     * @param tenantId 租户；与 runId 共同构成隔离键
     * @param runId    运行标识
     */
    @Override
    public List<ScenarioArtifact> findByRun(String tenantId, String runId) {
        return jdbc.query("SELECT * FROM scenario_artifact WHERE tenant_id=? AND run_id=? ORDER BY created_at",
                (rs, n) -> artifact(rs), tenantId, runId);
    }

    private ScenarioArtifact artifact(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new ScenarioArtifact(rs.getString("id"), rs.getString("tenant_id"),
                rs.getString("run_id"), rs.getString("artifact_type"), rs.getString("name"),
                rs.getString("content"), rs.getString("content_hash"),
                rs.getTimestamp("created_at").toInstant());
    }
}
