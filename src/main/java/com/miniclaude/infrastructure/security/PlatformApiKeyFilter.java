package com.miniclaude.infrastructure.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * 平台 API 的最小入口认证边界。
 *
 * <p>配置 API 密钥后，所有 {@code /api/} 请求必须提供匹配的
 * {@code X-Platform-Api-Key}；未配置时仅允许环回地址，以避免开发默认配置意外暴露。
 * 本过滤器只做入口认证，不替代租户授权、业务权限或传输层加密。
 */
@Component
public class PlatformApiKeyFilter extends OncePerRequestFilter {

    private final String configuredApiKey;

    public PlatformApiKeyFilter(
            @Value("${platform.security.api-key:}") String configuredApiKey) {
        this.configuredApiKey = configuredApiKey == null ? "" : configuredApiKey.trim();
    }

    /** 仅过滤平台 API 路径；静态资源及非 API 端点留给其各自安全链处理。 */
    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    /**
     * 在请求进入控制器前执行失败关闭认证。
     *
     * <p>请求与响应由 Servlet 容器提供。密钥不匹配、缺失，或无密钥模式下来源不是环回地址，
     * 都直接返回 401 且不继续过滤链。方法不修改共享状态，可并发执行，也不产生可重试副作用。
     */
    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (StringUtils.hasText(configuredApiKey)) {
            String provided = request.getHeader("X-Platform-Api-Key");
            // 使用常量时间字节比较，降低根据响应时间逐步猜测密钥的风险。
            if (!constantTimeEquals(configuredApiKey, provided)) {
                unauthorized(response);
                return;
            }
        } else if (!isLoopback(request.getRemoteAddr())) {
            // 未配置密钥不是“关闭认证”，而是收紧到本机开发访问。
            unauthorized(response);
            return;
        }
        filterChain.doFilter(request, response);
    }

    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    private static boolean isLoopback(String address) {
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (Exception ignored) {
            return false;
        }
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"error\":\"unauthorized\",\"message\":\"A valid platform API key is required\"}");
    }
}
