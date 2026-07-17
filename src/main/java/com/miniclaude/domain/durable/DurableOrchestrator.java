package com.miniclaude.domain.durable;

import com.miniclaude.domain.platform.AgentRun;
import com.miniclaude.domain.platform.ExecutionMode;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * 持久执行的供应商无关编排端口。
 *
 * <p>调用方只表达运行状态转换，不感知状态由本地数据库还是 Temporal 保存。所有会产生
 * 持久副作用的方法都携带幂等键，使网络重试不会重复推进状态或重复记账；实现遇到非法状态、
 * 预算/超时边界或审批证据不足时必须拒绝继续执行（fail-closed）。</p>
 */
public interface DurableOrchestrator {
    /** 创建带步骤、成本和墙钟超时上限的运行。 */
    AgentRun create(String tenantId, String agentId, ExecutionMode executionMode, String goal, int maxSteps,
                    BigDecimal maxCostUsd, Duration timeout);
    /** 暂停运行；相同幂等键重放时返回已持久化结果。 */
    AgentRun pause(String tenantId, String runId, String idempotencyKey);
    /** 从暂停或已满足审批条件的状态恢复运行。 */
    AgentRun resume(String tenantId, String runId, String idempotencyKey);
    /** 取消非终态运行；终态不会被再次改写。 */
    AgentRun cancel(String tenantId, String runId, String idempotencyKey);
    /** 将运行置为等待审批，并把审批绑定到精确的动作参数。 */
    ApprovalRequest awaitApproval(String tenantId, String runId, String stepId, String actionType,
                                  String actionParameters, Duration ttl, String idempotencyKey);
    /** 原子记录步骤进度、成本、事件和可恢复 checkpoint。 */
    AgentRun recordStep(String tenantId, String runId, String stepId, String state,
                        BigDecimal stepCostUsd, String idempotencyKey);
    /** 保存最终状态并将运行转换为成功终态。 */
    AgentRun complete(String tenantId, String runId, String state, String idempotencyKey);
    /** 保存失败原因并将非终态运行转换为失败终态。 */
    AgentRun fail(String tenantId, String runId, String reason, String idempotencyKey);
}
