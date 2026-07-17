package com.miniclaude.domain.runtime;

import java.util.Objects;

/**
 * 策略判定的不可变输入。
 *
 * <p>动作与资源采用稳定文本标识，执行上下文提供租户和运行边界；本对象不执行
 * 规范化之外的资源解析，也不携带策略实现细节。
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
