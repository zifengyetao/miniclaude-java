package com.miniclaude.domain.durable;

import com.miniclaude.domain.platform.AgentRun;
import com.miniclaude.domain.platform.ExecutionMode;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * 持久执行的供应商无关编排端口（Durable Run 状态机入口）。
 * <p>
 * <b>为何放在 domain：</b>Run 生命周期（创建、暂停、恢复、审批、步进、终态）是平台核心用例，
 * 领域层声明契约，不感知 Local JDBC 或 Temporal Workflow。
 * <p>
 * <b>不变量：</b>
 * <ul>
 *   <li>所有副作用方法携带 {@code idempotencyKey}；相同键重放须返回已持久化结果，不得双写。</li>
 *   <li>非法状态转移、预算/超时/审批证据不足时 <b>fail-closed</b>（抛异常或拒绝）。</li>
 *   <li>终态 Run 不可被 pause/resume/recordStep 再次改写（cancel 对终态 no-op）。</li>
 * </ul>
 * <p>
 * <b>边界：</b>
 * <ul>
 *   <li><b>application</b>：PlatformRunController、Scenario 服务调用。</li>
 *   <li><b>infrastructure</b>：{@code LocalDurableOrchestrator}（默认）或 Temporal 边界实现。</li>
 * </ul>
 */
public interface DurableOrchestrator {

    /**
     * 创建带步骤、成本和墙钟超时上限的运行。
     * <p>
     * <b>状态转移：</b>无 → {@link AgentRun.Status#PENDING}（持久化后可能立即 → RUNNING，由实现决定）。
     *
     * @param tenantId       租户 ID
     * @param agentId        员工定义 ID
     * @param executionMode  执行模式
     * @param goal           运行目标
     * @param maxSteps       最大步数
     * @param maxCostUsd     费用上限，可为 null
     * @param timeout        墙钟超时，可为 null
     */
    AgentRun create(String tenantId, String agentId, ExecutionMode executionMode, String goal, int maxSteps,
                    BigDecimal maxCostUsd, Duration timeout);

    /**
     * 暂停运行。
     * <p>
     * <b>前置：</b>status ∈ {RUNNING, PLANNING}（实现可扩展）。
     * <b>转移：</b>→ {@link AgentRun.Status#PAUSED}。
     * 相同幂等键重放返回已持久化结果。
     */
    AgentRun pause(String tenantId, String runId, String idempotencyKey);

    /**
     * 从暂停或已满足审批条件的状态恢复运行。
     * <p>
     * <b>前置：</b>PAUSED，或 WAITING_APPROVAL 且关联审批 APPROVED 未过期。
     * <b>转移：</b>→ {@link AgentRun.Status#RUNNING}。
     */
    AgentRun resume(String tenantId, String runId, String idempotencyKey);

    /**
     * 取消非终态运行。
     * <p>
     * <b>转移：</b>非终态 → {@link AgentRun.Status#CANCELLED}；终态不变。
     */
    AgentRun cancel(String tenantId, String runId, String idempotencyKey);

    /**
     * 将运行置为等待审批，并创建 {@link ApprovalRequest}。
     * <p>
     * <b>转移：</b>RUNNING → {@link AgentRun.Status#WAITING_APPROVAL}。
     * actionParameters 与后续 decide 必须完全一致（actionHash 绑定）。
     */
    ApprovalRequest awaitApproval(String tenantId, String runId, String stepId, String actionType,
                                  String actionParameters, Duration ttl, String idempotencyKey);

    /**
     * 原子记录步骤进度、累计成本、追加 {@link RunEvent} 与 {@link RunCheckpoint}。
     * <p>
     * <b>前置：</b>非终态；currentStep+1 ≤ maxSteps；累计 cost 未超 maxCostUsd。
     * <b>转移：</b>通常保持 RUNNING，步数递增；超限时 → STEP_LIMIT_EXCEEDED / BUDGET_EXCEEDED。
     */
    AgentRun recordStep(String tenantId, String runId, String stepId, String state,
                        BigDecimal stepCostUsd, String idempotencyKey);

    /**
     * 原子记录审批节点 checkpoint 并创建审批请求。
     *
     * <p>实现必须保证二者同事务提交，禁止出现「next-node 已推进但审批不存在」的绕过窗口。</p>
     */
    AgentRun recordStepAndAwaitApproval(String tenantId, String runId, String stepId, String state,
                                        BigDecimal stepCostUsd, String actionType,
                                        String actionParameters, Duration ttl,
                                        String idempotencyKey);

    /**
     * 原子记录 TERMINAL 节点 checkpoint 并将 Run 转为成功终态。
     *
     * <p>实现必须保证二者同事务提交，禁止留下 next-node 为空但 Run 仍为 RUNNING 的孤儿状态。</p>
     */
    AgentRun recordTerminalStep(String tenantId, String runId, String stepId, String state,
                                BigDecimal stepCostUsd, String idempotencyKey);

    /**
     * 保存最终状态并将运行转换为成功终态。
     * <p>
     * <b>转移：</b>→ {@link AgentRun.Status#SUCCEEDED}。
     */
    AgentRun complete(String tenantId, String runId, String state, String idempotencyKey);

    /**
     * 保存失败原因并将非终态运行转换为失败终态。
     * <p>
     * <b>转移：</b>→ {@link AgentRun.Status#FAILED}。
     */
    AgentRun fail(String tenantId, String runId, String reason, String idempotencyKey);
}
