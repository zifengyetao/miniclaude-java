package com.miniclaude.domain.scenario;

import java.time.Instant;
import java.util.List;

public final class ScenarioArtifact {
    private final String id;
    private final String tenantId;
    private final String runId;
    private final String type;
    private final String name;
    private final String content;
    private final String contentHash;
    private final Instant createdAt;

    public ScenarioArtifact(String id, String tenantId, String runId, String type, String name,
                            String content, String contentHash, Instant createdAt) {
        this.id = id; this.tenantId = tenantId; this.runId = runId; this.type = type;
        this.name = name; this.content = content; this.contentHash = contentHash; this.createdAt = createdAt;
    }

    public interface Repository {
        ScenarioArtifact save(String tenantId, String runId, String type, String name, String content);
        List<ScenarioArtifact> findByRun(String tenantId, String runId);
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getRunId() { return runId; }
    public String getType() { return type; }
    public String getName() { return name; }
    public String getContent() { return content; }
    public String getContentHash() { return contentHash; }
    public Instant getCreatedAt() { return createdAt; }
}
