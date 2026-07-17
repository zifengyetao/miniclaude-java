package com.miniclaude.domain.scenario;

import java.time.Instant;
import java.util.List;

/**
 * 场景运行产生的不可变证据载体。
 *
 * <p>内容哈希用于把审批决定绑定到当时的提案或案例包，tenantId/runId 用于隔离查询；
 * artifact 本身只记录草稿、报告和安全阻断证据，不代表任何外部系统动作已发生。</p>
 */
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
        /** 保存内容及其哈希，供后续审批、恢复和审计核对。 */
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
