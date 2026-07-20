package com.miniclaude.domain.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
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
    /** 供策略检查的结构化动作参数（不可变顶层副本）。 */
    private final Map<String, Object> arguments;

    public PolicyRequest(ExecutionContext context, String action, String resource) {
        this(context, action, resource, Collections.emptyMap());
    }

    public PolicyRequest(ExecutionContext context, String action, String resource,
                         Map<String, Object> arguments) {
        this.context = Objects.requireNonNull(context, "context");
        this.action = requireText(action, "action");
        this.resource = requireText(resource, "resource");
        this.arguments = Collections.unmodifiableMap(new LinkedHashMap<>(
                arguments == null ? Collections.emptyMap() : arguments));
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

    /** @return Tool/动作参数；策略实现不得记录密钥类值 */
    public Map<String, Object> getArguments() {
        return arguments;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
