package com.miniclaude.domain.platform;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * 可持久化、可恢复的 Agent 运行快照。
 *
 * <p>该值对象描述一次运行在某一时刻的状态、预算和并发版本，不执行状态迁移，
 * 也不负责调度。实例不可变，持久化适配器可据此重建运行状态。
 */
public final class AgentRun {

    public enum Status {
        PENDING,
        PLANNING,
        WAITING_APPROVAL,
        PAUSED,
        RUNNING,
        VERIFYING,
        SUCCEEDED,
        FAILED,
        CANCELLED,
        TIMED_OUT,
        BUDGET_EXCEEDED,
        STEP_LIMIT_EXCEEDED
    }

    private final String id;
    private final String agentId;
    private final ExecutionMode executionMode;
    private final String goal;
    private final Status status;
    private final int currentStep;
    private final int maxSteps;
    private final BigDecimal maxCostUsd;
    private final Instant createdAt;
    private final Instant updatedAt;
    private final String tenantId;
    private final long version;
    private final BigDecimal costUsd;
    private final Instant timeoutAt;

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
     * 创建尚未被调度的新运行。
     *
     * <p>Agent、目标和执行模式必须有效，最大步数至少为一，费用上限若存在必须为正。
     * 每次调用生成新的 UUID 和时间戳，因而不具备幂等性；调用方若需请求去重，应在
     * 应用层提供幂等键。
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

    private static String requireText(String value, String field) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public String getId() { return id; }
    public String getAgentId() { return agentId; }
    public ExecutionMode getExecutionMode() { return executionMode; }
    public String getGoal() { return goal; }
    public Status getStatus() { return status; }
    public int getCurrentStep() { return currentStep; }
    public int getMaxSteps() { return maxSteps; }
    public BigDecimal getMaxCostUsd() { return maxCostUsd; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public String getTenantId() { return tenantId; }
    public long getVersion() { return version; }
    public BigDecimal getCostUsd() { return costUsd; }
    public Instant getTimeoutAt() { return timeoutAt; }
}
