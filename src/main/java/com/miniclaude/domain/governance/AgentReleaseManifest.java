package com.miniclaude.domain.governance;

import java.time.Instant;
import java.util.Map;

/**
 * 一次可复现发布的清单值对象（Release Manifest）。
 * <p>
 * <b>为何放在 domain：</b>发布时将 Prompt/Graph/Rule 等资产 pin 到精确版本+哈希，
 * 是治理与可审计运行的核心概念。
 * <p>
 * <b>不变量：</b>
 * <ul>
 *   <li>{@code assetPins} 为 type → {@code key@version#hash}，禁止 latest/通配符。</li>
 *   <li>{@code manifestHash} 在排序后的 pins 上计算，消除 Map 迭代顺序差异。</li>
 *   <li>对象不可变；Status 迁移由 application Registry 服务推进。</li>
 * </ul>
 * <p>
 * <b>边界：</b>application GovernanceController；infrastructure Flyway V3 + JDBC。
 */
public final class AgentReleaseManifest {

    /**
     * 清单发布状态。
     * <p>
     * <b>转移：</b>DRAFT ──校验+签名──► RELEASED ──废弃──► DEPRECATED
     * <br>RELEASED 成为 Run 基线；DEPRECATED 不删除历史清单内容。
     */
    public enum Status {
        /** 草稿，可修改 assetPins。 */
        DRAFT,
        /** 已发布，Run 可 pin 此 manifestHash。 */
        RELEASED,
        /** 已废弃，不推荐新 Run pin。 */
        DEPRECATED
    }

    /** 清单记录 ID。 */
    private final String id;
    /** 租户 ID。 */
    private final String tenantId;
    /** Agent 业务键（非 UUID，Catalog 内稳定）。 */
    private final String agentKey;
    /** 清单语义版本。 */
    private final String version;
    /** 发布状态。 */
    private final Status status;
    /** 资产类型 → pin 字符串（key@version#contentHash）；计算 manifestHash 前会排序。 */
    private final Map<String, String> assetPins;
    /** 整个清单（含 pins）的 SHA-256 摘要。 */
    private final String manifestHash;
    /** 可选数字签名（基础设施验证）。 */
    private final String signature;
    /** 创建人。 */
    private final String createdBy;
    /** 创建时间。 */
    private final Instant createdAt;

    public AgentReleaseManifest(String id, String tenantId, String agentKey, String version, Status status,
                                Map<String, String> assetPins, String manifestHash, String signature,
                                String createdBy, Instant createdAt) {
        this.id = id; this.tenantId = tenantId; this.agentKey = agentKey; this.version = version;
        this.status = status; this.assetPins = assetPins; this.manifestHash = manifestHash;
        this.signature = signature; this.createdBy = createdBy; this.createdAt = createdAt;
    }

    /** @return 清单 ID */
    public String getId() { return id; }
    /** @return 租户 ID */
    public String getTenantId() { return tenantId; }
    /** @return Agent 键 */
    public String getAgentKey() { return agentKey; }
    /** @return 清单版本 */
    public String getVersion() { return version; }
    /** @return 发布状态 */
    public Status getStatus() { return status; }
    /** @return 资产 pin 映射（调用方勿修改） */
    public Map<String, String> getAssetPins() { return assetPins; }
    /** @return 清单哈希 */
    public String getManifestHash() { return manifestHash; }
    /** @return 签名 */
    public String getSignature() { return signature; }
    /** @return 创建人 */
    public String getCreatedBy() { return createdBy; }
    /** @return 创建时间 */
    public Instant getCreatedAt() { return createdAt; }
}
