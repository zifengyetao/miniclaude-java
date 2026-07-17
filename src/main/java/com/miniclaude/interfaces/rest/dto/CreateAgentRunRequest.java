package com.miniclaude.interfaces.rest.dto;

import com.miniclaude.domain.platform.ExecutionMode;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * 创建持久化 Agent Run 的 HTTP 入参。
 *
 * <p>这里仅接收调用方可调整的运行边界，不允许客户端直接传入状态、当前步骤、
 * 已消耗成本等服务端事实，避免通过伪造运行状态绕过编排器。Bean Validation
 * 提供接口层的第一道约束，应用服务仍会再次执行模式授权、租户配额和预算校验。
 */
public class CreateAgentRunRequest {

    /** 目标数字员工的精确 ID；应用层会校验其存在且支持所选执行模式。 */
    @NotBlank
    private String agentId;

    /** CHAT、PLAN_EXECUTE、GOAL 或 GRAPH，不能由模型在运行中自行提升。 */
    @NotNull
    private ExecutionMode executionMode;

    /** 本次运行目标；限制长度以控制持久化、审计和提示词注入面。 */
    @NotBlank
    @Size(max = 2000)
    private String goal;

    /** 可选步骤预算；最终还会被服务端和 AgentSpec 上限收紧。 */
    @Min(1)
    @Max(200)
    private Integer maxSteps;

    /** 可选成本预算，必须为正数，编排器按累计成本 fail-closed。 */
    @Positive
    private BigDecimal maxCostUsd;

    /** 可选墙钟超时；到期后持久编排器终止后续副作用步骤。 */
    @Min(1)
    private Long timeoutSeconds;

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }
    public ExecutionMode getExecutionMode() { return executionMode; }
    public void setExecutionMode(ExecutionMode executionMode) {
        this.executionMode = executionMode;
    }
    public String getGoal() { return goal; }
    public void setGoal(String goal) { this.goal = goal; }
    public Integer getMaxSteps() { return maxSteps; }
    public void setMaxSteps(Integer maxSteps) { this.maxSteps = maxSteps; }
    public BigDecimal getMaxCostUsd() { return maxCostUsd; }
    public void setMaxCostUsd(BigDecimal maxCostUsd) { this.maxCostUsd = maxCostUsd; }
    public Long getTimeoutSeconds() { return timeoutSeconds; }
    public void setTimeoutSeconds(Long timeoutSeconds) { this.timeoutSeconds = timeoutSeconds; }
}
