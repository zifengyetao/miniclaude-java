package com.miniclaude.domain.durable;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * 持久执行所需存储契约的命名空间。
 *
 * <p>契约按事件、checkpoint 和审批拆分，便于编排层只依赖领域能力；实现仍需在同一运行上
 * 协调事务和序列号，以保证恢复时看到一致且可排序的历史。</p>
 */
public final class DurableStores {
    private DurableStores() {}

    /** 仅追加的运行事实存储；幂等键重复时不得产生第二条事实。 */
    public interface RunEventStore {
        /** 追加事件，或在相同幂等键与相同内容重放时返回原事件。 */
        RunEvent append(String tenantId, String runId, String stepId, String type,
                        String idempotencyKey, String payload);
        /** 按运行内全局序列返回事件。 */
        List<RunEvent> findEvents(String tenantId, String runId);
    }

    /** 可恢复状态快照存储；快照保留历史版本而非原地覆盖。 */
    public interface CheckpointStore {
        /** 保存步骤状态及其摘要，供中断后恢复与完整性核对。 */
        RunCheckpoint save(String tenantId, String runId, String stepId, String state);
        /** 返回运行全局序列上最新的 checkpoint。 */
        Optional<RunCheckpoint> latest(String tenantId, String runId);
        /** 按运行内全局序列返回所有 checkpoint。 */
        List<RunCheckpoint> findCheckpoints(String tenantId, String runId);
    }

    /**
     * 人工审批契约。
     *
     * <p>批准对象必须绑定申请时的动作参数摘要；参数变化、过期或并发重复决策均应
     * fail-closed，不能把“曾批准过”解释为对任意后续动作的授权。</p>
     */
    public interface ApprovalService {
        /** 创建有有效期的待审批请求。 */
        ApprovalRequest request(String tenantId, String runId, String stepId, String actionType,
                                String actionParameters, Duration ttl);
        /** 对参数完全一致且仍有效的请求作出一次性决定。 */
        ApprovalRequest decide(String tenantId, String approvalId, String expectedActionParameters,
                               ApprovalRequest.Status decision, String actor, String reason);
        /** 按租户隔离查找审批，同时使已过期的待审批状态可见。 */
        Optional<ApprovalRequest> find(String tenantId, String approvalId);
        /** 按运行内全局序列返回审批历史。 */
        List<ApprovalRequest> findApprovals(String tenantId, String runId);
    }
}
