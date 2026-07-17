package com.miniclaude.infrastructure.runtime;

import com.miniclaude.domain.runtime.ModelGateway;
import com.miniclaude.domain.runtime.ModelRequest;
import com.miniclaude.domain.runtime.ModelResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 显式注册的模型路由；没有匹配路由时拒绝调用。 */
@Component
public class ControlledModelGateway implements ModelGateway {
    private final Map<String, ModelGateway> routes = new ConcurrentHashMap<>();

    public void register(String model, ModelGateway gateway) {
        if (model == null || model.trim().isEmpty() || gateway == null) {
            throw new IllegalArgumentException("model route and gateway are required");
        }
        routes.put(model.trim(), gateway);
    }

    @Override
    public ModelResult complete(ModelRequest request) {
        ModelGateway route = routes.get(request.getModel());
        if (route == null) throw new IllegalStateException("model route is not registered");
        return route.complete(request);
    }
}
