package com.miniclaude.domain.runtime;

import java.util.Objects;

/**
 * 策略判定请求。
 */
public final class PolicyRequest {

    private final ExecutionContext context;
    private final String action;
    private final String resource;

    public PolicyRequest(ExecutionContext context, String action, String resource) {
        this.context = Objects.requireNonNull(context, "context");
        this.action = requireText(action, "action");
        this.resource = requireText(resource, "resource");
    }

    public ExecutionContext getContext() {
        return context;
    }

    public String getAction() {
        return action;
    }

    public String getResource() {
        return resource;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
