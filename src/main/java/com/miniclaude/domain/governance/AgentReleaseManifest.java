package com.miniclaude.domain.governance;

import java.time.Instant;
import java.util.Map;

/**
 * 一次可复现发布的清单值对象。
 *
 * <p>{@code assetPins} 不是“取最新版”的范围声明，而是资产类型到
 * {@code key@精确版本#内容哈希} 的绑定。版本定位资产，哈希再次约束实际字节内容；两者共同防止
 * 运行时漂移和数据库内容被静默替换。对象字段无 setter，持久化清单的发布状态只能由应用服务
 * 受控推进；调用方也不应修改传入或取出的 Map。</p>
 */
public final class AgentReleaseManifest {
    /** DRAFT 可验证待发布；RELEASED 已成为运行基线；DEPRECATED 仅表示停止推荐，不应改写历史清单。 */
    public enum Status { DRAFT, RELEASED, DEPRECATED }

    private final String id;
    private final String tenantId;
    private final String agentKey;
    private final String version;
    private final Status status;
    /** 按资产类型保存精确 pin；清单计算哈希前会排序，以消除 Map 迭代顺序造成的非确定性。 */
    private final Map<String, String> assetPins;
    private final String manifestHash;
    private final String signature;
    private final String createdBy;
    private final Instant createdAt;

    public AgentReleaseManifest(String id, String tenantId, String agentKey, String version, Status status,
                                Map<String, String> assetPins, String manifestHash, String signature,
                                String createdBy, Instant createdAt) {
        this.id = id; this.tenantId = tenantId; this.agentKey = agentKey; this.version = version;
        this.status = status; this.assetPins = assetPins; this.manifestHash = manifestHash;
        this.signature = signature; this.createdBy = createdBy; this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getAgentKey() { return agentKey; }
    public String getVersion() { return version; }
    public Status getStatus() { return status; }
    public Map<String, String> getAssetPins() { return assetPins; }
    public String getManifestHash() { return manifestHash; }
    public String getSignature() { return signature; }
    public String getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
}
