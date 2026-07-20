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
 * HTTP 请求边界的治理追踪上下文过滤器（SLF4J MDC）。
 *
 * <p><b>写入 MDC 的维度</b>：
 * <ul>
 *   <li>{@code traceId}：全链路追踪；客户端可经 {@code X-Trace-Id} 传入，否则自动生成 UUID</li>
 *   <li>{@code tenantId}：租户；默认 {@code default}，供策略/审计/multi-tenant 日志关联</li>
 *   <li>{@code runId}：可选，关联 Durable Run（{@code X-Run-Id}）</li>
 * </ul></p>
 *
 * <p><b>刻意不写入 MDC 的内容</b>：请求体、API Key、PII——避免日志泄密与高基数 Prometheus 标签。
 * 指标计数见 {@link com.miniclaude.infrastructure.governance.DeterministicPolicyEngine}。</p>
 *
 * <p><b>finally 清理的必要性</b>：Servlet 容器复用线程；若异常路径不 {@code MDC.remove}，
 * 下一请求可能继承错误 tenantId，导致审计串租户——属于 silent data corruption 类缺陷。</p>
 *
 * <p>Bean 名 {@code governanceMdcFilter} 便于与其他 Filter 排序配置区分。</p>
 */
@Component("governanceMdcFilter")
public class RequestContextFilter extends OncePerRequestFilter {
    /**
     * 每个 HTTP 请求：注入 MDC → 执行链 → finally 清理 MDC。
     *
     * <p>同时将 traceId 写回响应头，便于客户端与后端日志关联。</p>
     */
    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String traceId = headerOr(request, "X-Trace-Id", UUID.randomUUID().toString());
        String tenantId = headerOr(request, "X-Tenant-Id", "default");
        String runId = headerOr(request, "X-Run-Id", "");
        MDC.put("traceId", traceId);
        MDC.put("tenantId", tenantId);
        // runId 为空时不写入 MDC，避免无 Run 的 Chat 请求产生空标签
        if (!runId.isEmpty()) MDC.put("runId", runId);
        // 回写响应头，客户端无需解析日志即可拿到 traceId
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

    /**
     * 读取请求头，空白/null 时返回 fallback。
     *
     * @param request  当前请求
     * @param name     头名称
     * @param fallback 缺省值（非 null）
     */
    private static String headerOr(HttpServletRequest request, String name, String fallback) {
        String value = request.getHeader(name);
        return value == null || value.trim().isEmpty() ? fallback : value.trim();
    }
}
