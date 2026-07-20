package com.miniclaude.domain.durable;

import java.time.Instant;
import java.util.Objects;

/**
 * 不可变、仅追加的运行事实（Event Sourcing 风格审计记录）。
 * <p>
 * <b>为何放在 domain：</b>运行时间线上的离散事实是 Durable 核心模型，与存储表结构解耦。
 * <p>
 * <b>不变量：</b>
 * <ul>
 *   <li>{@code sequence} 在单 run 内严格递增，全局排序审计与重放。</li>
 *   <li>{@code idempotencyKey} 约束同一业务操作只产生一条事实；键相同但 type/payload 不同须拒绝。</li>
 *   <li>{@code payload} null 归一化为 {@code "{}"}；{@code payloadHash} 为稳定摘要。</li>
 * </ul>
 * <p>
 * <b>边界：</b>由 {@link DurableStores.RunEventStore#append} 写入；infrastructure JDBC 实现序列号分配。
 */
public final class RunEvent {

    /** 事件记录 ID（存储层生成）。 */
    private final String id;
    /** 租户隔离键。 */
    private final String tenantId;
    /** 所属 Run ID。 */
    private final String runId;
    /** 关联步骤 ID，可为 null（Run 级事件）。 */
    private final String stepId;
    /** Run 内单调递增序列号（从 0 或 1 起，由 store 保证连续）。 */
    private final long sequence;
    /** 事件类型（如 STEP_STARTED、APPROVAL_REQUESTED，由 application 约定）。 */
    private final String type;
    /** 业务幂等键，与 append 调用方一致。 */
    private final String idempotencyKey;
    /** JSON 或其它序列化载荷；null → "{}"。 */
    private final String payload;
    /** payload 内容哈希，用于检测幂等冲突与篡改。 */
    private final String payloadHash;
    /** 记录版本（乐观锁/模式演进）。 */
    private final long version;
    /** 创建时间戳。 */
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

    /** @return 事件 ID */
    public String getId() { return id; }
    /** @return 租户 ID */
    public String getTenantId() { return tenantId; }
    /** @return Run ID */
    public String getRunId() { return runId; }
    /** @return 步骤 ID */
    public String getStepId() { return stepId; }
    /** @return Run 内序列号 */
    public long getSequence() { return sequence; }
    /** @return 事件类型 */
    public String getType() { return type; }
    /** @return 幂等键 */
    public String getIdempotencyKey() { return idempotencyKey; }
    /** @return 载荷 JSON 等 */
    public String getPayload() { return payload; }
    /** @return 载荷哈希 */
    public String getPayloadHash() { return payloadHash; }
    /** @return 记录版本 */
    public long getVersion() { return version; }
    /** @return 创建时间 */
    public Instant getCreatedAt() { return createdAt; }
}
