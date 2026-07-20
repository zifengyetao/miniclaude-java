package com.miniclaude.domain.platform;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 可持久化、可恢复的 Agent 运行快照（不可变值对象）。
 * <p>
 * <b>为何放在 domain：</b>描述一次数字员工 Run 在某一时刻的状态、预算与版本，
 * 供 Durable 编排与应用层查询；不执行状态迁移或调度（由 {@link com.miniclaude.domain.durable.DurableOrchestrator} 负责）。
 * <p>
 * <b>不变量：</b>
 * <ul>
 *   <li>{@code 0 ≤ currentStep ≤ maxSteps}，且 {@code maxSteps ≥ 1}。</li>
 *   <li>{@code maxCostUsd} 若非 null 必须为正；{@code costUsd} 非 null（可为 ZERO）。</li>
 *   <li>实例构造后不可变；状态变更通过编排器创建新快照并 {@link AgentRunRepository#save}。</li>
 * </ul>
 * <p>
 * <b>边界：</b>application 启动/查询 Run；infrastructure {@code LocalDurableOrchestrator} 推进状态并持久化。
 */
public final class AgentRun {

    /**
     * 运行生命周期状态。
     * <p>
     * <b>状态转移（由 {@link com.miniclaude.domain.durable.DurableOrchestrator} 实现，非法转移 fail-closed）：</b>
     * <pre>
     * PENDING ──create/start──► PLANNING / RUNNING
     * PLANNING ──步进──► RUNNING | WAITING_APPROVAL | FAILED
     * RUNNING ──pause──► PAUSED
     * RUNNING ──需审批动作──► WAITING_APPROVAL
     * RUNNING ──步进/验证──► VERIFYING | SUCCEEDED | FAILED
     * WAITING_APPROVAL ──批准+resume──► RUNNING | PAUSED
     * WAITING_APPROVAL ──拒绝/超时──► FAILED | CANCELLED
     * PAUSED ──resume──► RUNNING
     * PAUSED / 非终态 ──cancel──► CANCELLED
     * VERIFYING ──通过──► SUCCEEDED
     * VERIFYING ──失败──► FAILED
     * 任意非终态 ──超时──► TIMED_OUT
     * 任意非终态 ──累计成本超 maxCostUsd──► BUDGET_EXCEEDED
     * 任意非终态 ──currentStep ≥ maxSteps──► STEP_LIMIT_EXCEEDED
     * 终态（SUCCEEDED/FAILED/CANCELLED/TIMED_OUT/BUDGET_EXCEEDED/STEP_LIMIT_EXCEEDED）不可再迁移
     * </pre>
     */
    public enum Status {
        /** 已创建，尚未被调度执行。 */
        PENDING,
        /** 正在生成/调整执行计划。 */
        PLANNING,
        /** 阻塞于人工审批（见 {@link com.miniclaude.domain.durable.ApprovalRequest}）。 */
        WAITING_APPROVAL,
        /** 用户或系统主动暂停，可 resume。 */
        PAUSED,
        /** 正在执行步骤（主活跃态）。 */
        RUNNING,
        /** 执行完毕，进入结果验证阶段。 */
        VERIFYING,
        /** 成功终态。 */
        SUCCEEDED,
        /** 失败终态（含业务失败、审批拒绝等）。 */
        FAILED,
        /** 取消终态。 */
        CANCELLED,
        /** 超过 {@code timeoutAt} 墙钟时间。 */
        TIMED_OUT,
        /** 累计 {@code costUsd} 超过 {@code maxCostUsd}。 */
        BUDGET_EXCEEDED,
        /** {@code currentStep} 达到 {@code maxSteps} 上限仍未成功完成。 */
        STEP_LIMIT_EXCEEDED
    }

    /** 运行唯一 ID（UUID 字符串）。 */
    private final String id;
    /** 关联的 {@link AgentDefinition#getId()}。 */
    private final String agentId;
    /** 本次运行的执行模式，须属于定义允许集合。 */
    private final ExecutionMode executionMode;
    /** 用户或系统给出的运行目标描述。 */
    private final String goal;
    /** 当前生命周期状态。 */
    private final Status status;
    /** 已完成步数（0 起算）。 */
    private final int currentStep;
    /** 允许的最大步数（≥1）。 */
    private final int maxSteps;
    /** 费用上限（美元），null 表示不限制。 */
    private final BigDecimal maxCostUsd;
    /** 创建时间。 */
    private final Instant createdAt;
    /** 最后更新时间（每次状态/步进变更）。 */
    private final Instant updatedAt;
    /** 租户 ID，多租户隔离键。 */
    private final String tenantId;
    /** 乐观并发版本，每次持久化更新递增。 */
    private final long version;
    /** 已累计费用（美元）。 */
    private final BigDecimal costUsd;
    /** 墙钟超时截止时间，null 表示无超时。 */
    private final Instant timeoutAt;

    /**
     * 简化构造（tenantId=default, version=0, costUsd=ZERO, timeoutAt=null）。
     * 供测试或向后兼容；生产路径应使用全字段构造。
     */
    public AgentRun(
            String id,
            String agentId,
            ExecutionMode executionMode,
            String goal,
            Status status,
            int currentStep,
            int maxSteps,
            BigDecimal maxCostUsd,
            Instant createdAt,
            Instant updatedAt) {
        this(id, agentId, executionMode, goal, status, currentStep, maxSteps, maxCostUsd,
                createdAt, updatedAt, "default", 0, BigDecimal.ZERO, null);
    }

    /**
     * 全字段构造，执行步数与费用不变量校验。
     *
     * @throws IllegalArgumentException 步数边界或 maxCostUsd 非正
     */
    public AgentRun(
            String id, String agentId, ExecutionMode executionMode, String goal, Status status,
            int currentStep, int maxSteps, BigDecimal maxCostUsd, Instant createdAt,
            Instant updatedAt, String tenantId, long version, BigDecimal costUsd,
            Instant timeoutAt) {
        this.id = requireText(id, "id");
        this.agentId = requireText(agentId, "agentId");
        this.executionMode = Objects.requireNonNull(executionMode, "executionMode");
        this.goal = requireText(goal, "goal");
        this.status = Objects.requireNonNull(status, "status");
        // 步数不变量：当前步非负、至少一步、且不超过上限
        if (currentStep < 0 || maxSteps < 1 || currentStep > maxSteps) {
            throw new IllegalArgumentException("invalid step bounds");
        }
        if (maxCostUsd != null && maxCostUsd.signum() <= 0) {
            throw new IllegalArgumentException("maxCostUsd must be positive");
        }
        this.currentStep = currentStep;
        this.maxSteps = maxSteps;
        this.maxCostUsd = maxCostUsd;
        this.createdAt = Objects.requireNonNull(createdAt, "createdAt");
        this.updatedAt = Objects.requireNonNull(updatedAt, "updatedAt");
        this.tenantId = requireText(tenantId, "tenantId");
        this.version = version;
        this.costUsd = Objects.requireNonNull(costUsd, "costUsd");
        this.timeoutAt = timeoutAt;
    }

    /**
     * 创建尚未被调度的新运行（状态 PENDING，currentStep=0）。
     * <p>
     * 每次调用生成新 UUID 与时间戳，<b>非幂等</b>；去重由 application 幂等键 + 编排器负责。
     *
     * @param agentId       员工定义 ID
     * @param executionMode 执行模式
     * @param goal          运行目标
     * @param maxSteps      最大步数（≥1）
     * @param maxCostUsd    费用上限，可为 null
     * @return 新的 PENDING 运行快照
     */
    public static AgentRun pending(
            String agentId,
            ExecutionMode executionMode,
            String goal,
            int maxSteps,
            BigDecimal maxCostUsd) {
        Instant now = Instant.now();
        return new AgentRun(
                UUID.randomUUID().toString(),
                agentId,
                executionMode,
                goal,
                Status.PENDING,
                0,
                maxSteps,
                maxCostUsd,
                now,
                now);
    }

    /** 校验非空白文本并 trim。 */
    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    /** @return 运行 ID */
    public String getId() { return id; }
    /** @return 员工定义 ID */
    public String getAgentId() { return agentId; }
    /** @return 执行模式 */
    public ExecutionMode getExecutionMode() { return executionMode; }
    /** @return 运行目标 */
    public String getGoal() { return goal; }
    /** @return 当前状态 */
    public Status getStatus() { return status; }
    /** @return 当前步数 */
    public int getCurrentStep() { return currentStep; }
    /** @return 最大步数 */
    public int getMaxSteps() { return maxSteps; }
    /** @return 费用上限 */
    public BigDecimal getMaxCostUsd() { return maxCostUsd; }
    /** @return 创建时间 */
    public Instant getCreatedAt() { return createdAt; }
    /** @return 更新时间 */
    public Instant getUpdatedAt() { return updatedAt; }
    /** @return 租户 ID */
    public String getTenantId() { return tenantId; }
    /** @return 乐观锁版本 */
    public long getVersion() { return version; }
    /** @return 已累计费用 */
    public BigDecimal getCostUsd() { return costUsd; }
    /** @return 超时截止时间 */
    public Instant getTimeoutAt() { return timeoutAt; }
}
