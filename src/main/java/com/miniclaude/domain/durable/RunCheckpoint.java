package com.miniclaude.domain.durable;

import java.time.Instant;

/**
 * 运行在某一步完成后的不可变状态快照。
 *
 * <p>恢复逻辑按 {@code sequence} 选择最近快照；同一步可有多个 {@code version}，
 * 因而重试不会覆盖审计历史。{@code stateHash} 用于检测持久状态是否与写入时一致，
 * 它不是加密或访问控制机制。</p>
 */
public final class RunCheckpoint {
    private final String id;
    private final String tenantId;
    private final String runId;
    private final String stepId;
    private final long sequence;
    private final long version;
    private final String state;
    private final String stateHash;
    private final Instant createdAt;

    public RunCheckpoint(String id, String tenantId, String runId, String stepId, long sequence,
                         long version, String state, String stateHash, Instant createdAt) {
        this.id = id;
        this.tenantId = tenantId;
        this.runId = runId;
        this.stepId = stepId;
        this.sequence = sequence;
        this.version = version;
        this.state = state;
        this.stateHash = stateHash;
        this.createdAt = createdAt;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getRunId() { return runId; }
    public String getStepId() { return stepId; }
    public long getSequence() { return sequence; }
    public long getVersion() { return version; }
    public String getState() { return state; }
    public String getStateHash() { return stateHash; }
    public Instant getCreatedAt() { return createdAt; }
}
