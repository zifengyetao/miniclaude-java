package com.miniclaude.infrastructure.runtime;

import com.miniclaude.domain.runtime.HarnessModelGateway;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Harness 模型白名单路由；未注册模型 fail-closed，不做隐式供应商 fallback。 */
@Component
public class ControlledHarnessModelGateway implements HarnessModelGateway {
    private final Map<String, HarnessModelGateway> routes = new ConcurrentHashMap<>();

    public void register(String model, HarnessModelGateway gateway) {
        if (model == null || model.trim().isEmpty() || gateway == null) {
            throw new IllegalArgumentException("model route and gateway are required");
        }
        HarnessModelGateway existing = routes.putIfAbsent(model.trim(), gateway);
        if (existing != null) {
            throw new IllegalStateException("harness model route is already registered");
        }
    }

    @Override
    public ModelTurn next(ModelTurnRequest request) {
        HarnessModelGateway route = routes.get(request.getModel());
        if (route == null) throw new IllegalStateException("harness model route is not registered");
        return route.next(request);
    }
}
