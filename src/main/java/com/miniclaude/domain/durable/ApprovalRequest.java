package com.miniclaude.domain.durable;

import java.time.Instant;

public final class ApprovalRequest {
    public enum Status { PENDING, APPROVED, REJECTED, EXPIRED }

    private final String id;
    private final String tenantId;
    private final String runId;
    private final String stepId;
    private final long sequence;
    private final long version;
    private final String actionType;
    private final String actionParameters;
    private final String actionHash;
    private final Status status;
    private final Instant requestedAt;
    private final Instant expiresAt;
    private final Instant decidedAt;
    private final String decidedBy;
    private final String decisionReason;

    public ApprovalRequest(String id, String tenantId, String runId, String stepId, long sequence,
                           long version, String actionType, String actionParameters, String actionHash,
                           Status status, Instant requestedAt, Instant expiresAt, Instant decidedAt,
                           String decidedBy, String decisionReason) {
        this.id = id; this.tenantId = tenantId; this.runId = runId; this.stepId = stepId;
        this.sequence = sequence; this.version = version; this.actionType = actionType;
        this.actionParameters = actionParameters; this.actionHash = actionHash; this.status = status;
        this.requestedAt = requestedAt; this.expiresAt = expiresAt; this.decidedAt = decidedAt;
        this.decidedBy = decidedBy; this.decisionReason = decisionReason;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getRunId() { return runId; }
    public String getStepId() { return stepId; }
    public long getSequence() { return sequence; }
    public long getVersion() { return version; }
    public String getActionType() { return actionType; }
    public String getActionParameters() { return actionParameters; }
    public String getActionHash() { return actionHash; }
    public Status getStatus() { return status; }
    public Instant getRequestedAt() { return requestedAt; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getDecidedBy() { return decidedBy; }
    public String getDecisionReason() { return decisionReason; }
}
