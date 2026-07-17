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
 * 平台第一阶段的最小 API 边界。
 * 配置 PLATFORM_API_KEY 后所有 /api 请求必须携带 X-Platform-Api-Key；
 * 未配置时只允许本机访问，避免开发默认配置暴露到网络。
 */
@Component
public class PlatformApiKeyFilter extends OncePerRequestFilter {

    private final String configuredApiKey;

    public PlatformApiKeyFilter(
            @Value("${platform.security.api-key:}") String configuredApiKey) {
        this.configuredApiKey = configuredApiKey == null ? "" : configuredApiKey.trim();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        if (StringUtils.hasText(configuredApiKey)) {
            String provided = request.getHeader("X-Platform-Api-Key");
            if (!constantTimeEquals(configuredApiKey, provided)) {
                unauthorized(response);
                return;
            }
        } else if (!isLoopback(request.getRemoteAddr())) {
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
