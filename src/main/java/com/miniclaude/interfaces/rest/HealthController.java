package com.miniclaude.interfaces.rest;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.Map;

/**
 * 健康检查 REST 端点。
 * <p>
 * <b>职责</b>：供负载均衡、Kubernetes 探针或运维脚本确认 JVM 进程与 Spring 容器已就绪。
 * <p>
 * <b>上游</b>：基础设施周期性 GET 请求。
 * <b>下游</b>：无业务依赖；不探测数据库或外部 API，故只能证明进程存活而非全链路可用。
 * <p>
 * <b>安全/约束</b>： intentionally 无鉴权，便于探针访问；不应在此端点返回敏感信息。
 */
@RestController
public class HealthController {

    /**
     * 返回固定 UP 状态。
     *
     * @return 单键 {@code status=UP} 的 JSON 对象
     * @implNote 同时映射 {@code /health} 与 {@code /api/v1/health}，兼容旧路径与版本化 API 前缀
     */
    @GetMapping({"/health", "/api/v1/health"})
    public Map<String, String> health() {
        return Collections.singletonMap("status", "UP");
    }
}
