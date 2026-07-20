package com.miniclaude.domain.scenario;

import java.time.Instant;
import java.util.List;

/**
 * 场景运行产生的不可变证据载体（Artifact）。
 * <p>
 * <b>为何放在 domain：</b>补丁提案、分析报告、阻断记录是场景 Run 的可审计产出，
 * 与外部系统是否真实执行无关。
 * <p>
 * <b>不变量：</b>
 * <ul>
 *   <li>{@code contentHash} 绑定 {@code content}，审批 decide 时可核对。</li>
 *   <li>Artifact 仅记录草稿/报告，<b>不代表</b> OMS/CRM 已发生写操作。</li>
 * </ul>
 * <p>
 * <b>边界：</b>application Scenario 服务写入；infrastructure JDBC 实现 {@link Repository}。
 */
public final class ScenarioArtifact {

    /** Artifact ID。 */
    private final String id;
    /** 租户 ID。 */
    private final String tenantId;
    /** 关联 Durable Run ID。 */
    private final String runId;
    /** 类型（如 PATCH_PROPOSAL、INVESTIGATION_REPORT）。 */
    private final String type;
    /** 展示名称。 */
    private final String name;
    /** 正文（JSON/Markdown 等）。 */
    private final String content;
    /** content 的稳定哈希。 */
    private final String contentHash;
    /** 创建时间。 */
    private final Instant createdAt;

    public ScenarioArtifact(String id, String tenantId, String runId, String type, String name,
                            String content, String contentHash, Instant createdAt) {
        this.id = id; this.tenantId = tenantId; this.runId = runId; this.type = type;
        this.name = name; this.content = content; this.contentHash = contentHash; this.createdAt = createdAt;
    }

    /**
     * 场景 Artifact 持久化 Outbound Port。
     */
    public interface Repository {
        /**
         * 保存内容并计算 contentHash。
         *
         * @param content 正文
         */
        ScenarioArtifact save(String tenantId, String runId, String type, String name, String content);

        /**
         * 使用调用方稳定幂等键保存；同键同内容返回原制品，同键不同内容必须拒绝。
         */
        ScenarioArtifact saveIdempotent(String tenantId, String runId, String type, String name,
                                        String content, String idempotencyKey);

        /** 按 Run 查询全部 Artifact（审批/恢复/审计）。 */
        List<ScenarioArtifact> findByRun(String tenantId, String runId);
    }

    /** @return ID */
    public String getId() { return id; }
    /** @return 租户 */
    public String getTenantId() { return tenantId; }
    /** @return Run ID */
    public String getRunId() { return runId; }
    /** @return 类型 */
    public String getType() { return type; }
    /** @return 名称 */
    public String getName() { return name; }
    /** @return 正文 */
    public String getContent() { return content; }
    /** @return 内容哈希 */
    public String getContentHash() { return contentHash; }
    /** @return 创建时间 */
    public Instant getCreatedAt() { return createdAt; }
}
