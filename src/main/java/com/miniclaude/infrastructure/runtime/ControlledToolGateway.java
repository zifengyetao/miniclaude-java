package com.miniclaude.infrastructure.runtime;

import com.miniclaude.domain.runtime.ToolGateway;
import com.miniclaude.domain.runtime.ToolRequest;
import com.miniclaude.domain.runtime.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具白名单注册与路由适配器。
 *
 * <p>只有进程内明确注册的工具才能执行，未命中时失败关闭，不提供名称猜测或默认工具。
 * 路由表支持并发读写；重复注册会替换同名路由。
 */
@Component
public class ControlledToolGateway implements ToolGateway {
    private final Map<String, ToolGateway> routes = new ConcurrentHashMap<>();

    /**
     * 注册或替换工具路由。名称和网关必须有效；覆盖注册不是条件更新，
     * 并发调用时最后完成的写入生效。
     */
    public void register(String toolName, ToolGateway gateway) {
        if (toolName == null || toolName.trim().isEmpty() || gateway == null) {
            throw new IllegalArgumentException("tool route and gateway are required");
        }
        routes.put(toolName.trim(), gateway);
    }

    /**
     * 将请求转发给白名单中的工具。请求或路由无效时抛出异常；工具副作用不保证幂等，
     * 本层不会自动重试。
     */
    @Override
    public ToolResult execute(ToolRequest request) {
        ToolGateway route = routes.get(request.getToolName());
        if (route == null) throw new IllegalStateException("tool is not registered");
        return route.execute(request);
    }
}
