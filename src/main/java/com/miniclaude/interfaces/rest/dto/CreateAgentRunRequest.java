package com.miniclaude.interfaces.rest.dto;

import com.miniclaude.domain.platform.ExecutionMode;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Positive;
import javax.validation.constraints.Size;
import java.math.BigDecimal;

public class CreateAgentRunRequest {

    @NotBlank
    private String agentId;
    @NotNull
    private ExecutionMode executionMode;
    @NotBlank
    @Size(max = 2000)
    private String goal;
    @Min(1)
    @Max(200)
    private Integer maxSteps;
    @Positive
    private BigDecimal maxCostUsd;
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
