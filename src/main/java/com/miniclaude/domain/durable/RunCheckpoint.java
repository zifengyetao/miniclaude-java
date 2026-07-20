package com.miniclaude.domain.durable;

import java.time.Instant;

/**
 * 运行在某一步完成后的不可变状态快照（Checkpoint）。
 * <p>
 * <b>为何放在 domain：</b>中断恢复需要「可序列化的步骤状态 + 完整性哈希」，属于 Durable 领域模型。
 * <p>
 * <b>不变量：</b>
 * <ul>
 *   <li>{@code sequence} 与 {@link RunEvent} 对齐，恢复时取 sequence 最大者。</li>
 *   <li>同一步可有多个 {@code version}（重试追加，不覆盖历史）。</li>
 *   <li>{@code stateHash} 用于检测持久化内容与写入时不一致，非加密机制。</li>
 * </ul>
 * <p>
 * <b>边界：</b>{@link DurableStores.CheckpointStore#save} 写入；编排器 recordStep/complete 时更新。
 */
public final class RunCheckpoint {

    /** Checkpoint 记录 ID。 */
    private final String id;
    /** 租户 ID。 */
    private final String tenantId;
    /** Run ID。 */
    private final String runId;
    /** 步骤 ID（Graph 节点或线性步标识）。 */
    private final String stepId;
    /** Run 内全局序列，与事件序对齐。 */
    private final long sequence;
    /** 同一步骤内的版本号（重试递增）。 */
    private final long version;
    /** 序列化状态 JSON（编排器私有格式）。 */
    private final String state;
    /** state 内容的稳定哈希。 */
    private final String stateHash;
    /** 创建时间。 */
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

    /** @return Checkpoint ID */
    public String getId() { return id; }
    /** @return 租户 ID */
    public String getTenantId() { return tenantId; }
    /** @return Run ID */
    public String getRunId() { return runId; }
    /** @return 步骤 ID */
    public String getStepId() { return stepId; }
    /** @return 全局序列 */
    public long getSequence() { return sequence; }
    /** @return 步骤内版本 */
    public long getVersion() { return version; }
    /** @return 状态 JSON */
    public String getState() { return state; }
    /** @return 状态哈希 */
    public String getStateHash() { return stateHash; }
    /** @return 创建时间 */
    public Instant getCreatedAt() { return createdAt; }
}
