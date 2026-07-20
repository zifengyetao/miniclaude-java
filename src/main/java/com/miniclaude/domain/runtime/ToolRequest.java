package com.miniclaude.domain.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 工具执行的不可变请求 DTO。
 * <p>
 * <b>为何放在 domain：</b>统一 ToolGateway 入参。
 * <p>
 * <b>不变量：</b>toolName 非空白；arguments 顶层 Map 防御性复制（不深拷贝 value）。
 * <p>
 * <b>边界：</b>构造成功<b>不代表</b>已授权；策略/沙箱在 gateway 内再次校验。
 */
public final class ToolRequest {

    /** 执行边界。 */
    private final ExecutionContext context;
    /** 工具注册名（路由键）。 */
    private final String toolName;
    /** 工具参数（JSON 兼容 Map）。 */
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

    /** @return 上下文 */
    public ExecutionContext getContext() {
        return context;
    }

    /** @return 工具名 */
    public String getToolName() {
        return toolName;
    }

    /** @return 不可变参数映射 */
    public Map<String, Object> getArguments() {
        return arguments;
    }
}
