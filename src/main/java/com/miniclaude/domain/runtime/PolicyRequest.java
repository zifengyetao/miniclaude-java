package com.miniclaude.domain.runtime;

import java.util.Objects;

/**
 * 策略判定的不可变输入 DTO。
 * <p>
 * <b>为何放在 domain：</b>统一 PolicyEngine 与 ExternalPolicyAdapter 入参。
 * <p>
 * <b>不变量：</b>action/resource 非空白 trim；context 非 null。
 * <p>
 * <b>边界：</b>不负责资源 URI 规范化之外的语义解析。
 */
public final class PolicyRequest {

    /** 租户/Run 边界。 */
    private final ExecutionContext context;
    /** 动作标识（如 tool:write_file、order:draft）。 */
    private final String action;
    /** 资源标识（如 path、instrument、customerId）。 */
    private final String resource;

    public PolicyRequest(ExecutionContext context, String action, String resource) {
        this.context = Objects.requireNonNull(context, "context");
        this.action = requireText(action, "action");
        this.resource = requireText(resource, "resource");
    }

    /** @return 执行上下文 */
    public ExecutionContext getContext() {
        return context;
    }

    /** @return 动作 */
    public String getAction() {
        return action;
    }

    /** @return 资源 */
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
