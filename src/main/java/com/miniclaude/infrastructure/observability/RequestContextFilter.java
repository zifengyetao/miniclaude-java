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

/** 在请求边界设置并清理低基数上下文；正文和敏感值不进入指标标签。 */
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
