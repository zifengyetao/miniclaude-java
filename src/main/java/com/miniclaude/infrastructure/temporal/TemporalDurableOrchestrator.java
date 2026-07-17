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
        runs.save(run);
        DurableRunWorkflow workflow = client.newWorkflowStub(DurableRunWorkflow.class,
                WorkflowOptions.newBuilder().setTaskQueue("miniclaude-durable")
                        .setWorkflowId("run-" + run.getId()).build());
        DurableRunWorkflow.Input input = new DurableRunWorkflow.Input();
        input.tenantId = tenantId; input.runId = run.getId(); input.maxSteps = maxSteps;
        input.timeoutSeconds = timeout.getSeconds();
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
        throw new IllegalStateException("approval requests must be emitted by a Temporal Activity");
    }
    @Override public AgentRun recordStep(String tenantId, String runId, String stepId, String state,
            BigDecimal stepCostUsd, String key) {
        throw new IllegalStateException("steps must be recorded by a Temporal Activity");
    }
    @Override public AgentRun complete(String tenantId, String runId, String state, String key) {
        throw new IllegalStateException("completion must be recorded by a Temporal Activity");
    }
    @Override public AgentRun fail(String tenantId, String runId, String reason, String key) {
        throw new IllegalStateException("failure must be recorded by a Temporal Activity");
    }

    private DurableRunWorkflow workflow(String runId) {
        return client.newWorkflowStub(DurableRunWorkflow.class, "run-" + runId);
    }
    private AgentRun required(String tenantId, String runId) {
        AgentRun run = runs.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("run not found"));
        if (!run.getTenantId().equals(tenantId)) throw new IllegalArgumentException("run not found");
        return run;
    }
}
