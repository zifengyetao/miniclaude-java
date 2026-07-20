package com.miniclaude.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Mini Claude 平台外部化配置项（Spring Boot {@code @ConfigurationProperties}）。
 *
 * <p><b>绑定前缀</b>：{@code miniclaude.*}，可在 {@code application.yml}、
 * {@code application-{profile}.yml} 或环境变量（{@code MINICLAUDE_MODEL} 等）中设置。</p>
 *
 * <p><b>消费方</b>：{@link DomainConfig#agentSettings(MiniClaudeProperties)} 将其转为
 * 领域 {@link com.miniclaude.domain.agent.AgentSettings}；运行时与工作区安全另有
 * {@code platform.*} 前缀配置（见 {@link com.miniclaude.infrastructure.runtime.LocalWorkspaceSecurity}）。</p>
 *
 * <p><b>与 docs 硬约束的关系</b>：本类只承载配置字段，不执行校验；API Key 为空时
 * {@link com.miniclaude.infrastructure.runtime.LegacyAgentRuntime} 会在执行前 fail-closed。</p>
 */
@ConfigurationProperties(prefix = "miniclaude")
public class MiniClaudeProperties {

    /** 模型覆盖；为空时从环境变量或提供商默认值解析。 */
    private String model = "";

    /** 显式 API Key；为空时依次尝试 MOONSHOT / OPENAI / ANTHROPIC 环境变量。 */
    private String apiKey = "";

    /** OpenAI 兼容 API 基础地址；为空且存在 Moonshot Key 时使用 Moonshot 默认地址。 */
    private String apiBase = "";

    /** 工具权限模式：bypassPermissions | default | acceptEdits | dontAsk | plan | auto。 */
    private String permissionMode = "default";

    /** 是否启用 thinking 模式。 */
    private boolean thinking;

    /** 单次会话最大费用（美元），{@code null} 表示不限制。 */
    private Double maxCostUsd;

    /** 单轮对话内 Agent 最大推理/工具轮次。 */
    private Integer maxTurns = 32;

    /** Agent 工作目录；为空时工具相对于进程当前工作目录执行。 */
    private String workingDirectory = "";

    public String getModel() {
        return model;
    }

    public void setModel(String model) {
        this.model = model;
    }

    public String getApiKey() {
        return apiKey;
    }

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public String getApiBase() {
        return apiBase;
    }

    public void setApiBase(String apiBase) {
        this.apiBase = apiBase;
    }

    public String getPermissionMode() {
        return permissionMode;
    }

    public void setPermissionMode(String permissionMode) {
        this.permissionMode = permissionMode;
    }

    public boolean isThinking() {
        return thinking;
    }

    public void setThinking(boolean thinking) {
        this.thinking = thinking;
    }

    public Double getMaxCostUsd() {
        return maxCostUsd;
    }

    public void setMaxCostUsd(Double maxCostUsd) {
        this.maxCostUsd = maxCostUsd;
    }

    public Integer getMaxTurns() {
        return maxTurns;
    }

    public void setMaxTurns(Integer maxTurns) {
        this.maxTurns = maxTurns;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    public void setWorkingDirectory(String workingDirectory) {
        this.workingDirectory = workingDirectory;
    }
}
