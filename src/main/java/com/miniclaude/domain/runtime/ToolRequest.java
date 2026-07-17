package com.miniclaude.domain.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 工具执行的不可变请求。
 *
 * <p>工具名用于受控路由，参数映射只做顶层防御性复制；调用方和网关仍须校验参数模式、
 * 敏感值及工作区权限，不能把此对象的成功构造视为已授权。
 */
public final class ToolRequest {

    private final ExecutionContext context;
    private final String toolName;
    private final Map<String, Object> arguments;

    public ToolRequest(ExecutionContext context, String toolName, Map<String, Object> arguments) {
        this.context = Objects.requireNonNull(context, "context");
        if (toolName == null || toolName.trim().isEmpty()) {
            throw new IllegalArgumentException("toolName is required");
        }
        this.toolName = toolName.trim();
        this.arguments = arguments == null
                ? Collections.emptyMap()
                : Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
    }

    public ExecutionContext getContext() {
        return context;
    }

    public String getToolName() {
        return toolName;
    }

    public Map<String, Object> getArguments() {
        return arguments;
    }
}
