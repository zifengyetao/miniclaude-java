package com.miniclaude.infrastructure.temporal;

import com.miniclaude.domain.durable.ApprovalRequest;
import com.miniclaude.domain.durable.DurableOrchestrator;
import com.miniclaude.domain.platform.AgentRun;
import com.miniclaude.domain.platform.AgentRunRepository;
import com.miniclaude.domain.platform.ExecutionMode;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowOptions;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Temporal 持久编排适配器（{@link DurableOrchestrator} 的 Temporal 实现）。
 *
 * <p><b>激活条件</b>：{@code platform.orchestrator=temporal}（非默认；默认见
 * {@link LocalDurableOrchestrator}）。</p>
 *
 * <p><b>边界设计（为何部分方法 throw）</b>：
 * Temporal 要求<b>Workflow 代码确定性</b>——随机数、时钟、DB 写入必须在 Activity 中。
 * 若本类直接 {@code recordStep}/{@code complete}/{@code awaitApproval} 写 JDBC，
 * 会绕过 Workflow 历史与 Activity 重试语义，导致「DB 状态与 Temporal 历史分叉」。
 * 因此在 Activity 未接线前<b>fail-closed 抛异常</b>，而非静默写库。</p>
 *
 * <p><b>已实现能力</b>：{@link #create} 启动 Workflow、{@link #pause}/{@link #resume}/
 * {@link #cancel} 发送 Signal。控制 Signal 经 stub 发送，不创建新 Workflow 实例。</p>
 *
 * <p><b>Worker 职责</b>：{@link DurableRunWorkflow} 实现与 {@link DurableRunWorkflow.Activities}
 * 须在部署模块注册到 task queue {@code miniclaude-durable}——本类不包含 Worker 启动逻辑。</p>
 */
@Service
@ConditionalOnProperty(name = "platform.orchestrator", havingValue = "temporal")
public class TemporalDurableOrchestrator implements DurableOrchestrator {
    /** Temporal Workflow 客户端，由 {@link TemporalBoundaryConfiguration} 提供 */
    private final WorkflowClient client;
    /** 运行聚合快照仓储，create 时先落库再启 Workflow */
    private final AgentRunRepository runs;

    public TemporalDurableOrchestrator(WorkflowClient client, AgentRunRepository runs) {
        this.client = client; this.runs = runs;
    }

    @Override
    public AgentRun create(String tenantId, String agentId, ExecutionMode executionMode,
                           String goal, int maxSteps,
                           BigDecimal maxCostUsd, Duration timeout) {
        Instant now = Instant.now();
        AgentRun run = new AgentRun(UUID.randomUUID().toString(), agentId, executionMode,
                goal, AgentRun.Status.PENDING, 0, maxSteps, maxCostUsd, now, now, tenantId,
                0, BigDecimal.ZERO, now.plus(timeout));
        // 先保存可查询的运行标识，再以该标识构造唯一 Workflow ID；重复启动由 Temporal
        // 的 ID 唯一性阻止，而不会创建两个并行控制流。
        runs.save(run);
        DurableRunWorkflow workflow = client.newWorkflowStub(DurableRunWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue("miniclaude-durable")
                        .setWorkflowId("run-" + run.getId()).build());
        DurableRunWorkflow.Input input = new DurableRunWorkflow.Input();
        input.tenantId = tenantId; input.runId = run.getId(); input.maxSteps = maxSteps;
        input.timeoutSeconds = timeout.getSeconds();
        // 异步启动避免 REST 调用等待整个持久运行；后续状态通过 Workflow/Activity 推进。
        WorkflowClient.start(workflow::execute, input);
        return run;
    }

    /** 向 Workflow 发送 pause Signal；返回 DB 中当前 Run 快照（非 Query 最新态） */
    @Override public AgentRun pause(String tenantId, String runId, String key) {
        workflow(runId).pause(); return required(tenantId, runId);
    }
    /** 发送 resume Signal */
    @Override public AgentRun resume(String tenantId, String runId, String key) {
        workflow(runId).resume(); return required(tenantId, runId);
    }
    /** 发送 cancel Signal，阻止后续 Activity 副作用 */
    @Override public AgentRun cancel(String tenantId, String runId, String key) {
        workflow(runId).cancel(); return required(tenantId, runId);
    }
    @Override public ApprovalRequest awaitApproval(String tenantId, String runId, String stepId,
            String actionType, String actionParameters, Duration ttl, String key) {
        // 此处直接写库会绕过 Workflow 历史和 Activity 幂等重试，因此在边界未实现前明确拒绝。
        throw new IllegalStateException("approval requests must be emitted by a Temporal Activity");
    }
    @Override public AgentRun recordStep(String tenantId, String runId, String stepId, String state,
            BigDecimal stepCostUsd, String key) {
        // 步骤成本与 checkpoint 必须由 Activity 原子持久化，不能从控制面旁路写入。
        throw new IllegalStateException("steps must be recorded by a Temporal Activity");
    }
    @Override public AgentRun recordStepAndAwaitApproval(String tenantId, String runId,
            String stepId, String state, BigDecimal stepCostUsd, String actionType,
            String actionParameters, Duration ttl, String key) {
        throw new IllegalStateException("approval steps must be recorded by a Temporal Activity");
    }
    @Override public AgentRun recordTerminalStep(String tenantId, String runId, String stepId,
            String state, BigDecimal stepCostUsd, String key) {
        throw new IllegalStateException("terminal steps must be recorded by a Temporal Activity");
    }
    @Override public AgentRun complete(String tenantId, String runId, String state, String key) {
        // 终态必须成为 Workflow 历史的一部分；缺少 Activity 时宁可失败也不制造分叉状态。
        throw new IllegalStateException("completion must be recorded by a Temporal Activity");
    }
    @Override public AgentRun fail(String tenantId, String runId, String reason, String key) {
        // 与成功终态相同，失败事实也必须通过可重试、可审计的 Activity 落库。
        throw new IllegalStateException("failure must be recorded by a Temporal Activity");
    }

    /**
     * 获取已存在 Workflow 的 stub（仅发 Signal / Query，不 start）。
     *
     * @param runId 业务 Run id；WorkflowId 约定为 {@code run-{runId}}
     */
    private DurableRunWorkflow workflow(String runId) {
        // 已知 Workflow ID 的 stub 只发送 Signal，不会创建新的 Workflow 实例。
        return client.newWorkflowStub(DurableRunWorkflow.class, "run-" + runId);
    }

    /**
     * 加载 Run 并校验租户；跨租户访问统一报 not found（防枚举）。
     */
    private AgentRun required(String tenantId, String runId) {
        AgentRun run = runs.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("run not found"));
        // 隐藏跨租户运行是否存在，避免控制 Signal 成为标识枚举通道。
        if (!run.getTenantId().equals(tenantId)) throw new IllegalArgumentException("run not found");
        return run;
    }
}
