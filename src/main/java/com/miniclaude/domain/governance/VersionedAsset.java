package com.miniclaude.domain.governance;

import java.time.Instant;

public final class VersionedAsset {
    public enum Type { PROMPT, RULE, SKILL, GRAPH, VERIFIER, EVAL_SET }
    public enum Status { DRAFT, PUBLISHED, DEPRECATED, REVOKED }

    private final String id;
    private final String tenantId;
    private final Type type;
    private final String key;
    private final String version;
    private final String parentId;
    private final Status status;
    private final String content;
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
