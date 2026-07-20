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
 * <p>本类只声明传输层必填和长度约束，字段组合及执行模式业务规则由
 * {@link AgentDefinition} 领域对象和 {@link com.miniclaude.application.platform.AgentPlatformService} 校验；
 * 可变 setter 仅供 JSON 反序列化，不应作为领域模型跨层传递。
 */
public class CreateAgentDefinitionRequest {

    /** 显示名称，最长 120 字符。 */
    @NotBlank
    @Size(max = 120)
    private String name;
    /** 职责描述，最长 1000 字符。 */
    @NotBlank
    @Size(max = 1000)
    private String description;
    /** 绑定角色包/Role 名称，最长 120 字符。 */
    @NotBlank
    @Size(max = 120)
    private String roleName;
    /** 风险等级，影响审批与演进策略。 */
    @NotNull
    private AgentDefinition.RiskLevel riskLevel;
    /** 允许的自进化成熟度上限（L0–L3，禁止 L4）。 */
    @NotNull
    private AgentDefinition.EvolutionLevel evolutionLevel;
    /** 至少一种支持的执行模式（CHAT、GRAPH 等）。 */
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
