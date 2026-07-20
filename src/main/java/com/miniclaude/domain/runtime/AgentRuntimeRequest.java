package com.miniclaude.domain.runtime;

import com.miniclaude.domain.agent.AgentSettings;

import java.util.Objects;

/**
 * 单轮运行时执行请求（不可变 DTO）。
 * <p>
 * <b>为何放在 domain：</b>统一 Chat 与 Platform 向 Runtime 传参的形状。
 * <p>
 * <b>不变量：</b>context/settings 非 null；input 非空白。
 * <p>
 * <b>边界：</b>application 组装；不负责鉴权、策略、内容审核（在上层完成）。
 */
public final class AgentRuntimeRequest {

    /** 安全边界上下文。 */
    private final ExecutionContext context;
    /** 引擎运行时配置。 */
    private final AgentSettings settings;
    /** 用户或系统输入文本。 */
    private final String input;

    /**
     * @param context  非 null
     * @param settings 非 null
     * @param input    非 null 且非空白
     * @throws IllegalArgumentException input 为空
     */
    public AgentRuntimeRequest(ExecutionContext context, AgentSettings settings, String input) {
        this.context = Objects.requireNonNull(context, "context");
        this.settings = Objects.requireNonNull(settings, "settings");
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("input is required");
        }
        this.input = input;
    }

    /** @return 执行上下文 */
    public ExecutionContext getContext() {
        return context;
    }

    /** @return Agent 配置 */
    public AgentSettings getSettings() {
        return settings;
    }

    /** @return 输入文本 */
    public String getInput() {
        return input;
    }
}
