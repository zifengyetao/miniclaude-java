package com.miniclaude.infrastructure.runtime;

import com.miniclaude.domain.runtime.ToolGateway;
import com.miniclaude.domain.runtime.ToolRequest;
import com.miniclaude.domain.runtime.ToolResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工具调用白名单网关（{@link ToolGateway} 的路由/registry 实现）。
 *
 * <p><b>Harness-first 约束</b>：Agent 引擎只能调用启动时 {@link #register} 的工具名。
 * 未注册名称直接 {@link IllegalStateException}（fail-closed），<b>禁止</b>按名称猜测、
 * 反射加载或默认回退到 shell——否则策略引擎与沙箱边界可被绕过。</p>
 *
 * <p><b>与 {@link ControlledModelGateway} 对称</b>：模型与工具均采用「显式登记、默认拒绝」
 * 路由模式，符合 {@code docs/overview.md} 控制面包住概率模型的原则。</p>
 */
@Component
public class ControlledToolGateway implements ToolGateway {
    /** 工具名 → 实际网关实现；{@link ConcurrentHashMap} 支持运行时注册与并发读 */
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
