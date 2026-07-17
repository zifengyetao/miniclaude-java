package com.miniclaude.infrastructure.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 外部化配置项（前缀 {@code miniclaude.*}）。
 * <p>
 * 对应 application.yml / 环境变量，供基础设施层注入领域默认参数。
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
