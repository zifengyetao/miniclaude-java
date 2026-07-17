package com.miniclaude.infrastructure.runtime;

import com.miniclaude.domain.runtime.ToolGateway;
import com.miniclaude.domain.runtime.ToolRequest;
import com.miniclaude.domain.runtime.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 工具白名单注册表；未注册工具不可执行。 */
@Component
public class ControlledToolGateway implements ToolGateway {
    private final Map<String, ToolGateway> routes = new ConcurrentHashMap<>();

    public void register(String toolName, ToolGateway gateway) {
        if (toolName == null || toolName.trim().isEmpty() || gateway == null) {
            throw new IllegalArgumentException("tool route and gateway are required");
        }
        routes.put(toolName.trim(), gateway);
    }

    @Override
    public ToolResult execute(ToolRequest request) {
        ToolGateway route = routes.get(request.getToolName());
        if (route == null) throw new IllegalStateException("tool is not registered");
        return route.execute(request);
    }
}
