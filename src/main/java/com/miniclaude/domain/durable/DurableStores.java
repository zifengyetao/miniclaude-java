package com.miniclaude.domain.durable;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 持久执行所需存储契约的命名空间（细粒度 Outbound Port）。
 * <p>
 * <b>为何放在 domain：</b>事件、Checkpoint、审批是 Durable 的三大持久化能力，
 * 编排器 {@link DurableOrchestrator} 依赖这些端口，但不依赖 JDBC SQL。
 * <p>
 * <b>不变量：</b>实现须在单 Run 上协调事务与 sequence，恢复时事件/Checkpoint/审批时间线一致可排序。
 * <p>
 * <b>边界：</b>infrastructure {@code LocalDurableOrchestrator} + Flyway V2 表实现各 Store。
 */
public final class DurableStores {
    /** 工具类，禁止实例化。 */
    private DurableStores() {}

    /**
     * 仅追加的运行事实存储（Event Store 端口）。
     * <p>幂等：相同 idempotencyKey + 相同 payload → 返回原事件；键相同 payload 不同 → 拒绝。
     */
    public interface RunEventStore {
        /**
         * 追加事件，或在相同幂等键与相同内容重放时返回原事件。
         *
         * @param tenantId        租户
         * @param runId           Run
         * @param stepId          步骤，可为 null
         * @param type            事件类型
         * @param idempotencyKey  业务幂等键
         * @param payload         JSON 载荷
         */
        RunEvent append(String tenantId, String runId, String stepId, String type,
                        String idempotencyKey, String payload);

        /** 按 Run 内 sequence 升序返回全部事件（审计/重放）。 */
        List<RunEvent> findEvents(String tenantId, String runId);
    }

    /**
     * 可恢复状态快照存储（Checkpoint Store 端口）。
     * <p>快照保留历史版本，save 为追加而非 UPDATE 覆盖。
     */
    public interface CheckpointStore {
        /**
         * 保存步骤状态及其摘要。
         *
         * @param state 序列化状态 JSON
         */
        RunCheckpoint save(String tenantId, String runId, String stepId, String state);

        /** 返回 sequence 最大的 checkpoint（恢复入口）。 */
        Optional<RunCheckpoint> latest(String tenantId, String runId);

        /** 按 sequence 升序返回全部 checkpoint。 */
        List<RunCheckpoint> findCheckpoints(String tenantId, String runId);
    }

    /**
     * 人工审批契约（Approval Service 端口）。
     * <p>
     * decide 时 {@code expectedActionParameters} 必须与 request 时一致（哈希比对）；
     * 过期、拒绝、参数漂移均 fail-closed。
     */
    public interface ApprovalService {
        /**
         * 创建 PENDING 审批，expiresAt = now + ttl。
         *
         * @param actionParameters 待批准动作的精确参数 JSON
         */
        ApprovalRequest request(String tenantId, String runId, String stepId, String actionType,
                                String actionParameters, Duration ttl);

        /**
         * 对仍有效且参数一致的 PENDING 请求作一次性决定。
         * <p>
         * <b>转移：</b>PENDING → APPROVED | REJECTED；过期 → EXPIRED（由 store 或调用前检查）。
         *
         * @param expectedActionParameters 必须与 request 时 actionParameters 完全一致
         */
        ApprovalRequest decide(String tenantId, String approvalId, String expectedActionParameters,
                               ApprovalRequest.Status decision, String actor, String reason);

        /** 按租户隔离查找；查询时可将过期 PENDING 物化为 EXPIRED 状态返回。 */
        Optional<ApprovalRequest> find(String tenantId, String approvalId);

        /** 按 sequence 升序返回 Run 下全部审批历史。 */
        List<ApprovalRequest> findApprovals(String tenantId, String runId);
    }
}
