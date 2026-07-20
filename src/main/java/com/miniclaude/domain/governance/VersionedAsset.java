package com.miniclaude.domain.governance;

import java.time.Instant;

/**
 * 治理资产的单一、不可变版本（Registry 核心实体）。
 * <p>
 * <b>为何放在 domain：</b>Prompt/Rule/Graph 等内容版本化、哈希固定是 Harness-first 治理的基础。
 * <p>
 * <b>不变量：</b>
 * <ul>
 *   <li>同 tenant+type+key+version 唯一；发布后不原地覆盖，后继版本 {@code parentId} 指向父版。</li>
 *   <li>{@code contentHash} 必须与 {@code content} 一致，否则 fail-closed。</li>
 * </ul>
 * <p>
 * <b>边界：</b>application 治理服务 CRUD/发布；infrastructure JDBC + {@link GovernanceHash}。
 */
public final class VersionedAsset {

    /** 资产类型枚举。 */
    public enum Type {
        /** 提示词模板。 */
        PROMPT,
        /** 策略规则（可与 {@link PolicyRule} 关联）。 */
        RULE,
        /** Agent 技能定义。 */
        SKILL,
        /** {@link com.miniclaude.domain.graph.GraphSpec} 序列化。 */
        GRAPH,
        /** 输出/行为验证器。 */
        VERIFIER,
        /** 评测集（进化 gate）。 */
        EVAL_SET
    }

    /**
     * 资产版本生命周期。
     * <p>
     * <b>转移：</b>DRAFT ──发布──► PUBLISHED ──废弃──► DEPRECATED ──撤销──► REVOKED
     * <br>REVOKED 不可用于新 Run，历史 Run pin 仍保留内容供审计。
     */
    public enum Status {
        /** 草稿。 */
        DRAFT,
        /** 已发布，可 pin。 */
        PUBLISHED,
        /** 已废弃。 */
        DEPRECATED,
        /** 已撤销，禁止新 Run 引用。 */
        REVOKED
    }

    /** 资产记录 ID。 */
    private final String id;
    /** 租户 ID。 */
    private final String tenantId;
    /** 资产类型。 */
    private final Type type;
    /** 资产业务键（Catalog 内 stable key）。 */
    private final String key;
    /** 语义版本字符串。 */
    private final String version;
    /** 父版本 ID（首版可为 null）。 */
    private final String parentId;
    /** 发布状态。 */
    private final Status status;
    /** 资产正文（YAML/JSON/文本）。 */
    private final String content;
    /** content 的 SHA-256（{@link GovernanceHash}）。 */
    private final String contentHash;
    /** 可选签名。 */
    private final String signature;
    /** 创建人。 */
    private final String createdBy;
    /** 创建时间。 */
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

    /** @return 资产 ID */
    public String getId() { return id; }
    /** @return 租户 ID */
    public String getTenantId() { return tenantId; }
    /** @return 资产类型 */
    public Type getType() { return type; }
    /** @return 资产键 */
    public String getKey() { return key; }
    /** @return 版本 */
    public String getVersion() { return version; }
    /** @return 父版本 ID */
    public String getParentId() { return parentId; }
    /** @return 状态 */
    public Status getStatus() { return status; }
    /** @return 正文 */
    public String getContent() { return content; }
    /** @return 内容哈希 */
    public String getContentHash() { return contentHash; }
    /** @return 签名 */
    public String getSignature() { return signature; }
    /** @return 创建人 */
    public String getCreatedBy() { return createdBy; }
    /** @return 创建时间 */
    public Instant getCreatedAt() { return createdAt; }
}
