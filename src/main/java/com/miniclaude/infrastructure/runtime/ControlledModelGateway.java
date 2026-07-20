package com.miniclaude.infrastructure.runtime;

import com.miniclaude.domain.runtime.ModelGateway;
import com.miniclaude.domain.runtime.ModelRequest;
import com.miniclaude.domain.runtime.ModelResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 大模型调用白名单网关（{@link ModelGateway} 的路由实现）。
 *
 * <p><b>为何默认拒绝</b>：防止配置错误或 Prompt 注入导致请求被路由到未评审的供应商/模型，
 * 产生意外费用或数据出境。只有 {@link #register} 的模型名才会被 {@link #complete} 接受。</p>
 *
 * <p><b>无隐式 fallback</b>：与「OpenAI 兼容自动降级」不同，本类在 route miss 时
 * 直接失败，迫使部署显式声明可用模型列表。</p>
 */
@Component
public class ControlledModelGateway implements ModelGateway {
    /** 模型名 → 供应商网关；并发安全，重复 register 覆盖旧路由 */
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
