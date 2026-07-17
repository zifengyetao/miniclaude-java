package com.miniclaude.infrastructure.scenario;

import com.miniclaude.domain.scenario.ScenarioArtifact;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public class JdbcScenarioArtifactRepository implements ScenarioArtifact.Repository {
    private final JdbcTemplate jdbc;

    public JdbcScenarioArtifactRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public ScenarioArtifact save(String tenantId, String runId, String type, String name, String content) {
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
