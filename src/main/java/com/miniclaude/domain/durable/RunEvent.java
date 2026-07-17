package com.miniclaude.domain.durable;

import java.time.Instant;
import java.util.Objects;

/** 不可变、仅追加的运行事实。 */
public final class RunEvent {
    private final String id;
    private final String tenantId;
    private final String runId;
    private final String stepId;
    private final long sequence;
    private final String type;
    private final String idempotencyKey;
    private final String payload;
    private final String payloadHash;
    private final long version;
    private final Instant createdAt;

    public RunEvent(String id, String tenantId, String runId, String stepId, long sequence,
                    String type, String idempotencyKey, String payload, String payloadHash,
                    long version, Instant createdAt) {
        this.id = Objects.requireNonNull(id, "id");
        this.tenantId = Objects.requireNonNull(tenantId, "tenantId");
        this.runId = Objects.requireNonNull(runId, "runId");
        this.stepId = stepId;
        this.sequence = sequence;
        this.type = Objects.requireNonNull(type, "type");
        this.idempotencyKey = Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        this.payload = payload == null ? "{}" : payload;
        this.payloadHash = Objects.requireNonNull(payloadHash, "payloadHash");
        this.version = version;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getRunId() { return runId; }
    public String getStepId() { return stepId; }
    public long getSequence() { return sequence; }
    public String getType() { return type; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getPayload() { return payload; }
    public String getPayloadHash() { return payloadHash; }
    public long getVersion() { return version; }
    public Instant getCreatedAt() { return createdAt; }
}
