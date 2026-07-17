package com.miniclaude.infrastructure.runtime;

import com.miniclaude.domain.runtime.ModelGateway;
import com.miniclaude.domain.runtime.ModelRequest;
import com.miniclaude.domain.runtime.ModelResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 显式注册、默认拒绝的模型路由适配器。
 *
 * <p>模型名只会路由到进程内明确登记的网关，不进行隐式供应商回退。路由表支持并发读写，
 * 但重复注册会覆盖旧值，注册生命周期应由启动配置控制。
 */
@Component
public class ControlledModelGateway implements ModelGateway {
    private final Map<String, ModelGateway> routes = new ConcurrentHashMap<>();

    /**
     * 注册或替换模型路由。名称和网关必须有效；重复调用具有覆盖语义，
     * 与同名调用并发时，正在执行的请求可能观察到任一完整路由。
     */
    public void register(String model, ModelGateway gateway) {
        if (model == null || model.trim().isEmpty() || gateway == null) {
            throw new IllegalArgumentException("model route and gateway are required");
        }
        routes.put(model.trim(), gateway);
    }

    /**
     * 转发模型请求。请求必须非空且路由已注册，否则抛出异常；底层调用的失败、
     * 幂等和并发语义保持不变。
     */
    @Override
    public ModelResult complete(ModelRequest request) {
        ModelGateway route = routes.get(request.getModel());
        if (route == null) throw new IllegalStateException("model route is not registered");
        return route.complete(request);
    }
}
