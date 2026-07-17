package com.miniclaude.domain.durable;

import com.miniclaude.domain.platform.AgentRun;
import com.miniclaude.domain.platform.ExecutionMode;

import java.math.BigDecimal;
import java.time.Duration;

/** 可由本地数据库或 Temporal 实现的供应商无关编排端口。 */
public interface DurableOrchestrator {
    AgentRun create(String tenantId, String agentId, ExecutionMode executionMode, String goal, int maxSteps,
                    BigDecimal maxCostUsd, Duration timeout);
    AgentRun pause(String tenantId, String runId, String idempotencyKey);
    AgentRun resume(String tenantId, String runId, String idempotencyKey);
    AgentRun cancel(String tenantId, String runId, String idempotencyKey);
    ApprovalRequest awaitApproval(String tenantId, String runId, String stepId, String actionType,
                                  String actionParameters, Duration ttl, String idempotencyKey);
    AgentRun recordStep(String tenantId, String runId, String stepId, String state,
                        BigDecimal stepCostUsd, String idempotencyKey);
    AgentRun complete(String tenantId, String runId, String state, String idempotencyKey);
    AgentRun fail(String tenantId, String runId, String reason, String idempotencyKey);
}
