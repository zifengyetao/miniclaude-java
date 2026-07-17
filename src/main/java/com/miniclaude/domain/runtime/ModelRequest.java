package com.miniclaude.domain.runtime;

import java.util.Objects;

/**
 * 模型调用请求。
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
