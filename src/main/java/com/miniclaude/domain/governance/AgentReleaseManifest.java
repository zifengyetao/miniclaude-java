package com.miniclaude.domain.governance;

import java.time.Instant;
import java.util.Map;

public final class AgentReleaseManifest {
    public enum Status { DRAFT, RELEASED, DEPRECATED }

    private final String id;
    private final String tenantId;
    private final String agentKey;
    private final String version;
    private final Status status;
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
