package com.miniclaude.infrastructure.observability;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;

/**
 * 在 HTTP 请求边界设置并清理治理追踪上下文。
 *
 * <p>traceId、tenantId、runId 用于把策略、评测、审计和发布操作关联起来；请求正文和敏感值
 * 不进入 MDC/指标标签，避免秘密泄漏与高基数指标爆炸。finally 清理是线程池安全要求：
 * 若遗留 MDC，复用同一工作线程的下个租户可能继承错误上下文并污染日志取证。</p>
 */
@Component("governanceMdcFilter")
public class RequestContextFilter extends OncePerRequestFilter {
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = headerOr(request, "X-Trace-Id", UUID.randomUUID().toString());
        String tenantId = headerOr(request, "X-Tenant-Id", "default");
        String runId = headerOr(request, "X-Run-Id", "");
        MDC.put("traceId", traceId);
        MDC.put("tenantId", tenantId);
        if (!runId.isEmpty()) MDC.put("runId", runId);
        response.setHeader("X-Trace-Id", traceId);
        try {
            chain.doFilter(request, response);
        } finally {
            // 正常、异常和中断路径都必须清理，不能只依赖成功响应后的代码。
            MDC.remove("traceId");
            MDC.remove("tenantId");
            MDC.remove("runId");
        }
    }

    private static String headerOr(HttpServletRequest request, String name, String fallback) {
        String value = request.getHeader(name);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
