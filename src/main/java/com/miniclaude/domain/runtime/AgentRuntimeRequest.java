package com.miniclaude.domain.runtime;

import com.miniclaude.domain.agent.AgentSettings;

import java.util.Objects;

/**
 * 单轮运行时执行请求。
 *
 * <p>它把安全边界、引擎设置和用户输入作为一个不可变参数传给运行时；不负责鉴权、
 * 策略判定或输入内容审核。
 */
public final class AgentRuntimeRequest {

    private final ExecutionContext context;
    private final AgentSettings settings;
    private final String input;

    public AgentRuntimeRequest(ExecutionContext context, AgentSettings settings, String input) {
        this.context = Objects.requireNonNull(context, "context");
        this.settings = Objects.requireNonNull(settings, "settings");
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("input is required");
        }
        this.input = input;
    }

    public ExecutionContext getContext() {
        return context;
    }

    public AgentSettings getSettings() {
        return settings;
    }

    public String getInput() {
        return input;
    }
}
