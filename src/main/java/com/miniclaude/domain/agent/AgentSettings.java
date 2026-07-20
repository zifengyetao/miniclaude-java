package com.miniclaude.domain.agent;

import java.util.Objects;

/**
 * Agent 运行时配置（不可变值对象）。
 * <p>
 * <b>为何放在 domain：</b>模型路由、权限模式、成本/轮次上限是 Chat/Runtime 共用的领域参数，
 * 应在 domain 与 infrastructure 之间统一传递，避免 application 依赖具体 SDK 配置类。
 * <p>
 * <b>不变量：</b>
 * <ul>
 *   <li>构造后所有字段不可变；变更只能通过 {@link #withSessionOverrides} 生成新快照。</li>
 *   <li>{@code permissionMode} 默认 {@code "default"}；{@code maxCostUsd}/{@code maxTurns} 可为 null 表示不限制。</li>
 *   <li>equals/hashCode 覆盖全部字段，便于测试与缓存键比较。</li>
 * </ul>
 * <p>
 * <b>边界：</b>application 从环境/会话读取后组装；infrastructure 网关读取 apiKey/apiBase 发起 HTTP，
 * 密钥不得写回领域对象日志。
 */
public final class AgentSettings {

    /** 使用的 LLM 模型标识（如 gpt-4、claude-3）。 */
    private final String model;
    /** 工具调用权限模式，如 default / bypassPermissions；由引擎解释。 */
    private final String permissionMode;
    /** 上游 API 密钥；敏感，仅 infrastructure 使用。 */
    private final String apiKey;
    /** API 基础地址，决定 OpenAI 兼容或 Anthropic 协议端点。 */
    private final String apiBase;
    /** 是否按 OpenAI 兼容协议调用（与 apiBase 组合生效）。 */
    private final boolean useOpenAiCompatible;
    /** 是否启用扩展思考（thinking）模式。 */
    private final boolean thinking;
    /** 单次会话最大费用上限（美元）；{@code null} 表示不限制。 */
    private final Double maxCostUsd;
    /** 单轮对话内 Agent 最大工具/推理轮次；{@code null} 表示引擎默认。 */
    private final Integer maxTurns;
    /** Agent 工作目录，影响文件类工具的执行上下文。 */
    private final String workingDirectory;

    /** 私有构造：仅 Builder 与 {@link #withSessionOverrides} 可创建实例。 */
    private AgentSettings(Builder b) {
        this.model = b.model;
        // 权限模式缺省为 default，避免 null 导致引擎行为不确定
        this.permissionMode = b.permissionMode != null ? b.permissionMode : "default";
        this.apiKey = b.apiKey;
        this.apiBase = b.apiBase;
        this.useOpenAiCompatible = b.useOpenAiCompatible;
        this.thinking = b.thinking;
        this.maxCostUsd = b.maxCostUsd;
        this.maxTurns = b.maxTurns;
        this.workingDirectory = b.workingDirectory;
    }

    /** @return 新的 Builder 实例 */
    public static Builder builder() {
        return new Builder();
    }

    /** @return 模型标识 */
    public String getModel() {
        return model;
    }

    /** @return 工具权限模式 */
    public String getPermissionMode() {
        return permissionMode;
    }

    /** @return API 密钥（敏感） */
    public String getApiKey() {
        return apiKey;
    }

    /** @return API 基础 URL */
    public String getApiBase() {
        return apiBase;
    }

    /** @return 是否 OpenAI 兼容协议 */
    public boolean isUseOpenAiCompatible() {
        return useOpenAiCompatible;
    }

    /** @return 是否启用 thinking */
    public boolean isThinking() {
        return thinking;
    }

    /** @return 费用上限（美元），null 表示无上限 */
    public Double getMaxCostUsd() {
        return maxCostUsd;
    }

    /** @return 最大轮次，null 表示引擎默认 */
    public Integer getMaxTurns() {
        return maxTurns;
    }

    /** @return 工作目录路径字符串 */
    public String getWorkingDirectory() {
        return workingDirectory;
    }

    /**
     * 基于全局默认配置，叠加会话级模型与轮次覆盖，生成新的不可变配置快照。
     * <p>
     * 未传入的 override 保留原值；apiKey 等敏感/全局项不会被会话覆盖。
     *
     * @param model    会话级模型，null 则保留原 model
     * @param maxTurns 会话级最大轮次，null 则保留原 maxTurns
     * @return 新 AgentSettings 实例
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

    /**
     * 流式构建 {@link AgentSettings}。
     * <p>
     * 各 setter 返回 this 以支持链式调用；{@link #build()} 前未设字段可为 null（permissionMode 在构造时默认 default）。
     */
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

        /** @param v 模型标识 */
        public Builder model(String v) {
            this.model = v;
            return this;
        }

        /** @param v 权限模式 */
        public Builder permissionMode(String v) {
            this.permissionMode = v;
            return this;
        }

        /** @param v API 密钥 */
        public Builder apiKey(String v) {
            this.apiKey = v;
            return this;
        }

        /** @param v API 基础地址 */
        public Builder apiBase(String v) {
            this.apiBase = v;
            return this;
        }

        /** @param v 是否 OpenAI 兼容 */
        public Builder useOpenAiCompatible(boolean v) {
            this.useOpenAiCompatible = v;
            return this;
        }

        /** @param v 是否 thinking */
        public Builder thinking(boolean v) {
            this.thinking = v;
            return this;
        }

        /** @param v 费用上限（美元） */
        public Builder maxCostUsd(Double v) {
            this.maxCostUsd = v;
            return this;
        }

        /** @param v 最大轮次 */
        public Builder maxTurns(Integer v) {
            this.maxTurns = v;
            return this;
        }

        /** @param v 工作目录 */
        public Builder workingDirectory(String v) {
            this.workingDirectory = v;
            return this;
        }

        /** @return 不可变 AgentSettings */
        public AgentSettings build() {
            return new AgentSettings(this);
        }
    }
}
