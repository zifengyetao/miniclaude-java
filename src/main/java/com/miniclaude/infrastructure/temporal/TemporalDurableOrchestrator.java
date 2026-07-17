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
 * Temporal 控制面适配器。Workflow 实现与 Worker 应由部署模块注册；缺失时操作会明确失败，
 * 不会静默回退到非持久执行。
 *
 * <p>该适配器只负责启动 Workflow 和发送控制 Signal；步骤、审批和终态持久化必须发生在
 * Activity 边界内，以利用 Temporal 的重试语义并保持 Workflow 代码确定性。尚未提供对应
 * Activity 路径的操作会 fail-closed，防止数据库状态与 Workflow 历史分叉。</p>
 */
@Service
@ConditionalOnProperty(name = "platform.orchestrator", havingValue = "temporal")
public class TemporalDurableOrchestrator implements DurableOrchestrator {
    private final WorkflowClient client;
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

    @Override public AgentRun pause(String tenantId, String runId, String key) {
        workflow(runId).pause(); return required(tenantId, runId);
    }
    @Override public AgentRun resume(String tenantId, String runId, String key) {
        workflow(runId).resume(); return required(tenantId, runId);
    }
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
    @Override public AgentRun complete(String tenantId, String runId, String state, String key) {
        // 终态必须成为 Workflow 历史的一部分；缺少 Activity 时宁可失败也不制造分叉状态。
        throw new IllegalStateException("completion must be recorded by a Temporal Activity");
    }
    @Override public AgentRun fail(String tenantId, String runId, String reason, String key) {
        // 与成功终态相同，失败事实也必须通过可重试、可审计的 Activity 落库。
        throw new IllegalStateException("failure must be recorded by a Temporal Activity");
    }

    private DurableRunWorkflow workflow(String runId) {
        // 已知 Workflow ID 的 stub 只发送 Signal，不会创建新的 Workflow 实例。
        return client.newWorkflowStub(DurableRunWorkflow.class, "run-" + runId);
    }
    private AgentRun required(String tenantId, String runId) {
        AgentRun run = runs.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("run not found"));
        // 隐藏跨租户运行是否存在，避免控制 Signal 成为标识枚举通道。
        if (!run.getTenantId().equals(tenantId)) throw new IllegalArgumentException("run not found");
        return run;
    }
}
