package com.miniclaude.infrastructure.scenario;

import com.miniclaude.domain.scenario.ScenarioArtifact;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
/**
 * 场景制品的 JDBC 持久化适配器。
 *
 * <p>保存时对规范化内容计算 SHA-256，审批参数可以绑定该哈希以证明审批的是同一份提案。
 * 查询同时带 tenant_id 与 run_id，避免仅凭运行 ID 跨租户读取案例包、草稿或阻断原因。</p>
 */
public class JdbcScenarioArtifactRepository implements ScenarioArtifact.Repository {
    private final JdbcTemplate jdbc;

    public JdbcScenarioArtifactRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public ScenarioArtifact save(String tenantId, String runId, String type, String name, String content) {
        // null 统一为空串后再计算和落库，保证返回哈希与数据库内容完全一致。
        String normalized = content == null ? "" : content;
        ScenarioArtifact artifact = new ScenarioArtifact(UUID.randomUUID().toString(), tenantId, runId,
                type, name, normalized, com.miniclaude.infrastructure.durable.JdbcDurableStore.sha256(normalized),
                Instant.now());
        jdbc.update("INSERT INTO scenario_artifact(id,tenant_id,run_id,artifact_type,name,content,"
                        + "content_hash,created_at) VALUES (?,?,?,?,?,?,?,?)",
                artifact.getId(), tenantId, runId, type, name, normalized, artifact.getContentHash(),
                Timestamp.from(artifact.getCreatedAt()));
        return artifact;
    }

    @Override
    public List<ScenarioArtifact> findByRun(String tenantId, String runId) {
        return jdbc.query("SELECT * FROM scenario_artifact WHERE tenant_id=? AND run_id=? ORDER BY created_at",
                (rs, n) -> new ScenarioArtifact(rs.getString("id"), rs.getString("tenant_id"),
                        rs.getString("run_id"), rs.getString("artifact_type"), rs.getString("name"),
                        rs.getString("content"), rs.getString("content_hash"),
                        rs.getTimestamp("created_at").toInstant()), tenantId, runId);
    }
}
