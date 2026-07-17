package com.miniclaude.interfaces.rest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/**
 * 健康检查端点。
 * <p>
 * 供负载均衡或运维探活，确认服务进程正常运行。
 */
@RestController
public class HealthController {

    /**
     * 返回服务存活状态。
     */
    @GetMapping({"/health", "/api/v1/health"})
    public Map<String, String> health() {
        return Collections.singletonMap("status", "UP");
    }
}
