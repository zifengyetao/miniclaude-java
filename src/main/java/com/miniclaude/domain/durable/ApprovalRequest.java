package com.miniclaude.domain.durable;

import java.time.Instant;

/**
 * 不可变的人工审批记录（四眼原则 / 高风险动作门禁）。
 * <p>
 * <b>为何放在 domain：</b>审批状态、动作参数绑定与过期语义是监管与平台硬约束，独立于 UI 与 JDBC。
 * <p>
 * <b>不变量：</b>
 * <ul>
 *   <li>{@code actionHash} 绑定申请时的 {@code actionParameters}，参数变更视为新请求。</li>
 *   <li>PENDING 且未过期才可 APPROVED/REJECTED；过期 → EXPIRED，不授予执行权。</li>
 *   <li>终态（APPROVED/REJECTED/EXPIRED）不可再次 decide。</li>
 * </ul>
 * <p>
 * <b>边界：</b>{@link DurableStores.ApprovalService} 实现；application 暴露审批 API。
 */
public final class ApprovalRequest {

    /**
     * 审批生命周期状态。
     * <p>
     * <b>状态转移：</b>
     * PENDING ──decide(APPROVED)──► APPROVED（可 resume Run）
     * PENDING ──decide(REJECTED)──► REJECTED（Run 通常 fail）
     * PENDING ──expiresAt 到达──► EXPIRED（fail-closed，需重新申请）
     */
    public enum Status {
        /** 待人工决策。 */
        PENDING,
        /** 已批准：仅当 actionHash 与 resume 时参数一致才有效。 */
        APPROVED,
        /** 已拒绝：不授予执行权限。 */
        REJECTED,
        /** 已过期：PENDING 超时，等同拒绝。 */
        EXPIRED
    }

    /** 审批记录 ID。 */
    private final String id;
    /** 租户 ID。 */
    private final String tenantId;
    /** 关联 Run ID。 */
    private final String runId;
    /** 关联步骤/节点 ID。 */
    private final String stepId;
    /** Run 内全局序列（与 Event/Checkpoint 对齐）。 */
    private final long sequence;
    /** 乐观锁版本，防止并发双重 decide。 */
    private final long version;
    /** 动作类型（如 PLACE_ORDER_DRAFT、SEND_CRM）。 */
    private final String actionType;
    /** 动作参数 JSON（决定前不可变）。 */
    private final String actionParameters;
    /** actionParameters 的稳定哈希，decide 时必须匹配 expectedActionParameters 的哈希。 */
    private final String actionHash;
    /** 当前审批状态。 */
    private final Status status;
    /** 申请时间。 */
    private final Instant requestedAt;
    /** 过期时间；到达后 PENDING → EXPIRED。 */
    private final Instant expiresAt;
    /** 决策时间；PENDING 时为 null。 */
    private final Instant decidedAt;
    /** 决策人标识。 */
    private final String decidedBy;
    /** 决策理由（审计）。 */
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

    /** @return 审批 ID */
    public String getId() { return id; }
    /** @return 租户 ID */
    public String getTenantId() { return tenantId; }
    /** @return Run ID */
    public String getRunId() { return runId; }
    /** @return 步骤 ID */
    public String getStepId() { return stepId; }
    /** @return 全局序列 */
    public long getSequence() { return sequence; }
    /** @return 版本 */
    public long getVersion() { return version; }
    /** @return 动作类型 */
    public String getActionType() { return actionType; }
    /** @return 动作参数 JSON */
    public String getActionParameters() { return actionParameters; }
    /** @return 动作参数哈希 */
    public String getActionHash() { return actionHash; }
    /** @return 审批状态 */
    public Status getStatus() { return status; }
    /** @return 申请时间 */
    public Instant getRequestedAt() { return requestedAt; }
    /** @return 过期时间 */
    public Instant getExpiresAt() { return expiresAt; }
    /** @return 决策时间 */
    public Instant getDecidedAt() { return decidedAt; }
    /** @return 决策人 */
    public String getDecidedBy() { return decidedBy; }
    /** @return 决策理由 */
    public String getDecisionReason() { return decisionReason; }
}
