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
 * 平台 API 的最小入口认证边界（Servlet Filter）。
 *
 * <p><b>设计定位</b>：这是 HTTP 进入 Spring 控制器前的第一道「失败即拒绝」闸门，
 * 属于 {@code docs/security.md} 中描述的私有化部署最小防护，而非完整 IAM 体系。</p>
 *
 * <p><b>两种运行模式及为何这样约束</b>：
 * <ul>
 *   <li><b>已配置密钥</b>（{@code platform.security.api-key}）：所有 {@code /api/} 请求
 *       必须携带匹配的 {@code X-Platform-Api-Key} 请求头。未配置密钥就对外暴露 API
 *       是常见误操作，因此「有密钥则强制校验」。</li>
 *   <li><b>未配置密钥</b>：仅允许来自环回地址（127.0.0.1 / ::1）的请求。
 *       这不是「关闭认证」，而是<b>开发默认 fail-closed</b>——防止开发者忘记配密钥时，
 *       进程监听 0.0.0.0 导致整个平台对局域网/公网裸奔。</li>
 * </ul></p>
 *
 * <p><b>刻意不做的事</b>：租户隔离、RBAC、OAuth、TLS 终止、请求体审计。
 * 这些由上层 {@code RequestContextFilter}、策略引擎及业务服务分别承担。</p>
 *
 * <p><b>线程安全</b>：{@link #configuredApiKey} 在构造后不可变，{@link #doFilterInternal}
 * 无共享可变状态，可安全并发处理请求。</p>
 *
 * @see com.miniclaude.infrastructure.observability.RequestContextFilter 治理追踪上下文
 */
@Component
public class PlatformApiKeyFilter extends OncePerRequestFilter {

    /** 从配置注入的平台 API 密钥；空串表示进入「仅环回」模式。构造后不可变。 */
    private final String configuredApiKey;

    /**
     * 构造过滤器并规范化密钥配置。
     *
     * @param configuredApiKey {@code platform.security.api-key}，可为空；
     *                         {@code null} 与空白均视为未配置
     */
    public PlatformApiKeyFilter(
            @Value("${platform.security.api-key:}") String configuredApiKey) {
        // trim 避免 YAML/环境变量末尾空格导致「明明配了却永远 401」的隐蔽故障
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

    /**
     * 常量时间比较两个 UTF-8 字符串，降低时序侧信道猜测密钥的风险。
     *
     * <p>普通 {@code String.equals} 在首字节不匹配时会提前返回，攻击者可通过响应时间
     * 差异逐字节推断密钥；{@link MessageDigest#isEqual} 始终比较完整长度。</p>
     *
     * @param expected 配置中的期望密钥
     * @param actual   请求头提供的密钥；{@code null} 直接视为不匹配
     */
    private static boolean constantTimeEquals(String expected, String actual) {
        if (actual == null) {
            return false;
        }
        return MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.UTF_8),
                actual.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 判断远程地址是否为环回（本机）地址。
     *
     * <p>解析失败时返回 {@code false}（fail-closed），避免 DNS/格式异常被误当作合法来源。</p>
     *
     * @param address {@code HttpServletRequest#getRemoteAddr()} 返回值
     */
    private static boolean isLoopback(String address) {
        try {
            return InetAddress.getByName(address).isLoopbackAddress();
        } catch (Exception ignored) {
            // 无法解析的地址一律拒绝，不放宽到「允许访问」
            return false;
        }
    }

    /**
     * 写入统一 401 JSON 响应并终止过滤链。
     *
     * <p>消息故意不含「密钥错误 / 缺失 / 非环回」等细分原因，避免为攻击者提供枚举信息。</p>
     */
    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getWriter().write(
                "{\"error\":\"unauthorized\",\"message\":\"A valid platform API key is required\"}");
    }
}
