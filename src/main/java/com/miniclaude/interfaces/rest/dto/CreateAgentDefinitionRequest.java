package com.miniclaude.interfaces.rest.dto;

import com.miniclaude.domain.platform.AgentDefinition;
import com.miniclaude.domain.platform.ExecutionMode;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;
import java.util.Set;

/**
 * 创建数字员工草稿的 HTTP 请求 DTO。
 *
 * <p>本类只声明传输层必填和长度约束，字段组合及执行模式业务规则由领域对象和应用服务校验；
 * 可变 setter 仅供 JSON 反序列化使用，不应作为领域模型跨层传递。
 */
public class CreateAgentDefinitionRequest {

    @NotBlank
    @Size(max = 120)
    private String name;
    @NotBlank
    @Size(max = 1000)
    private String description;
    @NotBlank
    @Size(max = 120)
    private String roleName;
    @NotNull
    private AgentDefinition.RiskLevel riskLevel;
    @NotNull
    private AgentDefinition.EvolutionLevel evolutionLevel;
    @NotEmpty
    private Set<ExecutionMode> executionModes;

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getRoleName() { return roleName; }
    public void setRoleName(String roleName) { this.roleName = roleName; }
    public AgentDefinition.RiskLevel getRiskLevel() { return riskLevel; }
    public void setRiskLevel(AgentDefinition.RiskLevel riskLevel) { this.riskLevel = riskLevel; }
    public AgentDefinition.EvolutionLevel getEvolutionLevel() { return evolutionLevel; }
    public void setEvolutionLevel(AgentDefinition.EvolutionLevel evolutionLevel) {
        this.evolutionLevel = evolutionLevel;
    }
    public Set<ExecutionMode> getExecutionModes() { return executionModes; }
    public void setExecutionModes(Set<ExecutionMode> executionModes) {
        this.executionModes = executionModes;
    }
}
