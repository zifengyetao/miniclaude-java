package com.miniclaude.domain.governance;

import java.time.Instant;

/**
 * 治理资产的单一、不可变版本。
 *
 * <p>同一租户、类型、键和版本在数据库中唯一；发布后不原地覆盖，而是以 {@code parentId}
 * 指向稳定父版本创建后继。这样运行记录可以长期精确 pin 到旧版本，回滚也能恢复已知基线，
 * 而不会受“最新版”漂移影响。</p>
 */
public final class VersionedAsset {
    public enum Type { PROMPT, RULE, SKILL, GRAPH, VERIFIER, EVAL_SET }
    /** REVOKED 表示版本不可再用于新运行，但历史内容仍保留供审计与回滚取证。 */
    public enum Status { DRAFT, PUBLISHED, DEPRECATED, REVOKED }

    private final String id;
    private final String tenantId;
    private final Type type;
    private final String key;
    private final String version;
    private final String parentId;
    private final Status status;
    private final String content;
    /** 内容摘要在发布与解析时复算；不匹配时失败关闭，避免执行被篡改内容。 */
    private final String contentHash;
    private final String signature;
    private final String createdBy;
    private final Instant createdAt;

    public VersionedAsset(String id, String tenantId, Type type, String key, String version,
                          String parentId, Status status, String content, String contentHash,
                          String signature, String createdBy, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.type = type;
        this.key = key;
        this.version = version;
        this.parentId = parentId;
        this.status = status;
        this.content = content;
        this.contentHash = contentHash;
        this.signature = signature;
        this.createdBy = createdBy;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public Type getType() { return type; }
    public String getKey() { return key; }
    public String getVersion() { return version; }
    public String getParentId() { return parentId; }
    public Status getStatus() { return status; }
    public String getContent() { return content; }
    public String getContentHash() { return contentHash; }
    public String getSignature() { return signature; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
