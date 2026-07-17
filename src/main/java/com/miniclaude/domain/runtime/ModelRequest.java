package com.miniclaude.domain.runtime;

import java.util.Objects;

/**
 * 模型调用的不可变请求。
 *
 * <p>显式绑定执行上下文、模型路由名和提示词；只保证字段非空白，不负责提示词安全检查、
 * 模型授权或预算控制。
 */
public final class ModelRequest {

    private final ExecutionContext context;
    private final String model;
    private final String prompt;

    public ModelRequest(ExecutionContext context, String model, String prompt) {
        this.context = Objects.requireNonNull(context, "context");
        this.model = requireText(model, "model");
        this.prompt = requireText(prompt, "prompt");
    }

    public ExecutionContext getContext() {
        return context;
    }

    public String getModel() {
        return model;
    }

    public String getPrompt() {
        return prompt;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value;
    }
}
