package com.miniclaude.domain.agent;

import java.util.Objects;

/**
 * Agent 运行时配置（不可变值对象）。
 * <p>
 * 封装模型、鉴权、权限与成本限制等参数，供领域层与基础设施层统一传递。
 */
public final class AgentSettings {

    /** 使用的 LLM 模型标识。 */
    private final String model;
    /** 工具调用权限模式，如 bypassPermissions。 */
    private final String permissionMode;
    /** 上游 API 密钥。 */
    private final String apiKey;
    /** API 基础地址，决定走 OpenAI 兼容或 Anthropic 协议。 */
    private final String apiBase;
    /** 是否按 OpenAI 兼容协议调用。 */
    private final boolean useOpenAiCompatible;
    /** 是否启用扩展思考（thinking）模式。 */
    private final boolean thinking;
    /** 单次会话最大费用上限（美元），{@code null} 表示不限制。 */
    private final Double maxCostUsd;
    /** 单轮对话内 Agent 最大工具/推理轮次。 */
    private final Integer maxTurns;
    /** Agent 工作目录，影响文件类工具的执行上下文。 */
    private final String workingDirectory;

    private AgentSettings(Builder b) {
        this.model = b.model;
        this.permissionMode = b.permissionMode != null ? b.permissionMode : "default";
        this.apiKey = b.apiKey;
        this.apiBase = b.apiBase;
        this.useOpenAiCompatible = b.useOpenAiCompatible;
        this.thinking = b.thinking;
        this.maxCostUsd = b.maxCostUsd;
        this.maxTurns = b.maxTurns;
        this.workingDirectory = b.workingDirectory;
    }

    public static Builder builder() {
        return new Builder();
    }

    public String getModel() {
        return model;
    }

    public String getPermissionMode() {
        return permissionMode;
    }

    public String getApiKey() {
        return apiKey;
    }

    public String getApiBase() {
        return apiBase;
    }

    public boolean isUseOpenAiCompatible() {
        return useOpenAiCompatible;
    }

    public boolean isThinking() {
        return thinking;
    }

    public Double getMaxCostUsd() {
        return maxCostUsd;
    }

    public Integer getMaxTurns() {
        return maxTurns;
    }

    public String getWorkingDirectory() {
        return workingDirectory;
    }

    /**
     * 基于全局默认配置，叠加会话级模型与轮次覆盖，生成新的配置快照。
     */
    public AgentSettings withSessionOverrides(String model, Integer maxTurns) {
        Builder b = builder()
                .model(model != null ? model : this.model)
                .permissionMode(this.permissionMode)
                .apiKey(this.apiKey)
                .apiBase(this.apiBase)
                .useOpenAiCompatible(this.useOpenAiCompatible)
                .thinking(this.thinking)
                .maxCostUsd(this.maxCostUsd)
                .maxTurns(maxTurns != null ? maxTurns : this.maxTurns)
                .workingDirectory(this.workingDirectory);
        return b.build();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof AgentSettings)) {
            return false;
        }
        AgentSettings that = (AgentSettings) o;
        return useOpenAiCompatible == that.useOpenAiCompatible
                && thinking == that.thinking
                && Objects.equals(model, that.model)
                && Objects.equals(permissionMode, that.permissionMode)
                && Objects.equals(apiKey, that.apiKey)
                && Objects.equals(apiBase, that.apiBase)
                && Objects.equals(maxCostUsd, that.maxCostUsd)
                && Objects.equals(maxTurns, that.maxTurns)
                && Objects.equals(workingDirectory, that.workingDirectory);
    }

    @Override
    public int hashCode() {
        return Objects.hash(model, permissionMode, apiKey, apiBase, useOpenAiCompatible,
                thinking, maxCostUsd, maxTurns, workingDirectory);
    }

    /** 流式构建 {@link AgentSettings}。 */
    public static final class Builder {
        private String model;
        private String permissionMode;
        private String apiKey;
        private String apiBase;
        private boolean useOpenAiCompatible;
        private boolean thinking;
        private Double maxCostUsd;
        private Integer maxTurns;
        private String workingDirectory;

        public Builder model(String v) {
            this.model = v;
            return this;
        }

        public Builder permissionMode(String v) {
            this.permissionMode = v;
            return this;
        }

        public Builder apiKey(String v) {
            this.apiKey = v;
            return this;
        }

        public Builder apiBase(String v) {
            this.apiBase = v;
            return this;
        }

        public Builder useOpenAiCompatible(boolean v) {
            this.useOpenAiCompatible = v;
            return this;
        }

        public Builder thinking(boolean v) {
            this.thinking = v;
            return this;
        }

        public Builder maxCostUsd(Double v) {
            this.maxCostUsd = v;
            return this;
        }

        public Builder maxTurns(Integer v) {
            this.maxTurns = v;
            return this;
        }

        public Builder workingDirectory(String v) {
            this.workingDirectory = v;
            return this;
        }

        public AgentSettings build() {
            return new AgentSettings(this);
        }
    }
}
