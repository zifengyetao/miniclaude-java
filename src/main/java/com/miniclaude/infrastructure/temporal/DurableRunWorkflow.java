package com.miniclaude.infrastructure.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/**
 * Temporal 持久 Run 的 Workflow 边界契约（SDK 接口定义，无实现体）。
 *
 * <p><b>分层目的</b>：领域/应用只依赖 {@link com.miniclaude.domain.durable.DurableOrchestrator}，
 * 不 import Temporal 类型——本接口是 infrastructure 与 Temporal SDK 之间的<b>防腐层</b>。</p>
 *
 * <p><b>Workflow vs Activity</b>：
 * <ul>
 *   <li>Workflow：可重放控制流；{@link #execute}、Signal 处理须确定性</li>
 *   <li>{@link Activities}：DB、外部 IO、非确定性逻辑；可重试、至少一次投递</li>
 * </ul></p>
 *
 * <p>Signal = 异步控制意图；Query = 只读快照，不改变 Workflow 状态。</p>
 */
@WorkflowInterface
public interface DurableRunWorkflow {
    /** 启动并持续编排一个运行；实现必须保持 Temporal 重放确定性。 */
    @WorkflowMethod
    void execute(Input input);

    /** 请求暂停后续步骤。 */
    @SignalMethod void pause();
    /** 请求从暂停状态恢复。 */
    @SignalMethod void resume();
    /** 请求取消并阻止后续副作用。 */
    @SignalMethod void cancel();
    /** 提交与审批 ID、决定及动作摘要绑定的审批信号。 */
    @SignalMethod void approvalDecision(String approvalId, String decision, String actionHash);
    /** 返回当前持久进度的只读视图。 */
    @QueryMethod Snapshot snapshot();

    /** Workflow 的稳定启动参数；只包含可序列化且重放所需的数据。 */
    final class Input {
        public String tenantId;
        public String runId;
        public int maxSteps;
        public long timeoutSeconds;
    }

    /** Query 返回的最小状态视图，checkpoint 摘要用于核对恢复基线。 */
    final class Snapshot {
        public String runId;
        public String status;
        public int currentStep;
        public String checkpointHash;
    }

    /**
     * Workflow 调用的非确定性持久化 Activity 契约。
     *
     * <p>Activity 可能被 Temporal 至少一次投递，具体实现必须使用确定的幂等键，
     * 并在状态、事件或 checkpoint 不一致时失败而不是猜测成功。</p>
     */
    @ActivityInterface
    interface Activities {
        /** 从权威存储加载恢复快照。 */
        Snapshot load(String tenantId, String runId);
        /** 幂等持久化一次状态转换及其审计事件。 */
        void persistTransition(String tenantId, String runId, String eventType,
                               String idempotencyKey, String payload);
        /** 保存步骤完成后的恢复点。 */
        void persistCheckpoint(String tenantId, String runId, String stepId, String state);
    }
}
