package com.miniclaude.infrastructure.engine;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

/**
 * HTTP 客户端与指数退避重试工具。
 *
 * <p><b>职责</b>：封装 JDK 11 {@link HttpClient}，向 LLM API 发送 JSON POST
 * （含 SSE 流式 {@link #postJsonStream} 与非流式 {@link #postJson}），
 * 并对 429/503/529 及典型网络错误执行 {@link #withRetry} 指数退避。
 *
 * <p><b>在系统中的位置</b>：{@code infrastructure/engine} 层网络边界，
 * 供 {@link Agent} 调用 Anthropic Messages API 与 OpenAI 兼容后端时使用。
 *
 * <p><b>重试策略</b>：延迟 {@code min(1000 * 2^attempt, 30000) + random(0..999)} ms，
 * 重试前通过 {@link Ui#printRetry} 向用户展示原因。
 *
 * <p><b>线程安全</b>：共享 {@link #CLIENT} 线程安全；{@link #withRetry} 阻塞调用线程。
 */
public final class ApiHttp {

    /**
     * 进程级共享 HttpClient：30s 连接超时，跟随 3xx 重定向。
     * JDK HttpClient 实例创建成本较高，故全局复用。
     */
    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 私有构造，禁止实例化。 */
    private ApiHttp() {}

    /**
     * 获取共享的 {@link HttpClient} 单例。
     *
     * @return 全局 CLIENT 实例
     * @sideeffects 无
     */
    public static HttpClient client() {
        return CLIENT;
    }

    /**
     * 判断 HTTP 状态码是否属于可自动重试范围。
     *
     * <p>429 Too Many Requests、503 Service Unavailable、529 Overloaded
     * 均为 LLM 提供商常见的临时性错误。
     *
     * @param status HTTP 响应状态码
     * @return {@code true} 表示应触发 {@link #withRetry}
     */
    public static boolean isRetryableStatus(int status) {
        return status == 429 || status == 503 || status == 529;
    }

    /**
     * 判断异常（含嵌套 {@link Throwable#getCause()}）是否可重试。
     *
     * <p>除 {@link #isRetryableStatus} 外，还匹配消息中含 overloaded、
     * connection reset、timed out 等网络/transient 错误。
     *
     * @param error 待检查的异常；null 时返回 false
     * @return {@code true} 表示适合重试
     */
    public static boolean isRetryable(Throwable error) {
        if (error == null) {
            return false;
        }
        String msg = error.getMessage() != null ? error.getMessage().toLowerCase() : "";
        // ── 消息关键字匹配（不区分大小写）──
        if (msg.contains("overloaded") || msg.contains("econnreset") || msg.contains("etimedout")
                || msg.contains("connection reset") || msg.contains("timed out")) {
            return true;
        }
        if (error instanceof HttpStatusException) {
            return isRetryableStatus(((HttpStatusException) error).status);
        }
        // ── 递归检查 cause 链 ──
        Throwable cause = error.getCause();
        return cause != null && cause != error && isRetryable(cause);
    }

    /**
     * 对可重试失败执行指数退避重试。
     *
     * <p>首次失败 attempt=0，最多额外重试 {@code maxRetries} 次；
     * 不可重试或已达上限时原样抛出（RuntimeException 包装 checked 异常）。
     *
     * @param fn         待执行的 HTTP 调用（通常 lambda 包装 postJson*）
     * @param maxRetries 最大重试次数（不含首次尝试）
     * @param <T>        返回值类型
     * @return {@code fn.get()} 的成功结果
     * @throws RuntimeException 不可重试或重试用尽时的异常；中断时包装 {@link InterruptedException}
     * @sideeffects 可能 {@link Thread#sleep} 并调用 {@link Ui#printRetry}
     */
    public static <T> T withRetry(Supplier<T> fn, int maxRetries) {
        for (int attempt = 0; ; attempt++) {
            try {
                return fn.get();
            } catch (Exception error) {
                // ── 终止条件：重试耗尽或错误不可重试 ──
                if (attempt >= maxRetries || !isRetryable(error)) {
                    if (error instanceof RuntimeException) {
                        throw (RuntimeException) error;
                    }
                    throw new RuntimeException(error);
                }
                // 指数退避 + 抖动，上限 30s
                long delayMs = Math.min(1000L * (1L << attempt), 30000L)
                        + ThreadLocalRandom.current().nextInt(1000);
                String reason;
                if (error instanceof HttpStatusException) {
                    reason = "HTTP " + ((HttpStatusException) error).status;
                } else {
                    reason = error.getMessage() != null ? error.getMessage() : "network error";
                }
                Ui.printRetry(attempt + 1, maxRetries, reason);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException(ie);
                }
            }
        }
    }

    /**
     * 发送 JSON POST 并返回流式响应体（SSE / chunked）。
     *
     * <p>超时 10 分钟，适用于 LLM 长流式生成。可重试状态码抛
     * {@link HttpStatusException} 供 {@link #withRetry} 捕获。
     *
     * @param url     完整 API URL
     * @param body    JSON 请求体字符串
     * @param headers 额外 HTTP 头（如 Authorization）；null 时仅 Content-Type
     * @return 2xx 响应，body 为 {@link InputStream}（调用方负责关闭）
     * @throws IOException          网络 I/O 失败
     * @throws InterruptedException 等待响应被中断
     * @throws HttpStatusException  可重试或 4xx/5xx 错误
     * @sideeffects 发起 HTTP 请求；非 2xx 时可能关闭 response body
     */
    public static HttpResponse<InputStream> postJsonStream(
            String url, String body, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        // ── 合并自定义请求头 ──
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getValue() != null) {
                    b.header(e.getKey(), e.getValue());
                }
            }
        }
        HttpResponse<InputStream> resp = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        // ── 可重试状态：关闭 body 后抛异常触发重试 ──
        if (isRetryableStatus(resp.statusCode())) {
            try {
                resp.body().close();
            } catch (Exception ignored) {
            }
            throw new HttpStatusException(resp.statusCode(), "HTTP " + resp.statusCode());
        }
        // ── 非 2xx 不可重试：读取有限错误体后抛异常 ──
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            String errBody = readLimited(resp.body(), 4000);
            throw new HttpStatusException(resp.statusCode(),
                    "HTTP " + resp.statusCode() + ": " + errBody);
        }
        return resp;
    }

    /**
     * 发送 JSON POST 并返回完整响应字符串（非流式）。
     *
     * <p>超时 5 分钟，适用于 side query、分类器等短请求。
     *
     * @param url     完整 API URL
     * @param body    JSON 请求体
     * @param headers 额外 HTTP 头；null 时仅 Content-Type
     * @return 响应 body 字符串
     * @throws IOException          网络 I/O 失败
     * @throws InterruptedException 等待被中断
     * @throws HttpStatusException  HTTP 错误
     * @sideeffects 发起 HTTP 请求
     */
    public static String postJson(
            String url, String body, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getValue() != null) {
                    b.header(e.getKey(), e.getValue());
                }
            }
        }
        HttpResponse<String> resp = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofString());
        if (isRetryableStatus(resp.statusCode())) {
            throw new HttpStatusException(resp.statusCode(), "HTTP " + resp.statusCode());
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new HttpStatusException(resp.statusCode(),
                    "HTTP " + resp.statusCode() + ": " + resp.body());
        }
        return resp.body();
    }

    /**
     * 从 InputStream 读取最多 {@code max} 字节并转为 UTF-8 字符串。
     *
     * <p>用于错误响应体截断，避免大 body 占满内存；无论成功与否都会在 finally 关闭流。
     *
     * @param in  输入流
     * @param max 最大读取字节数
     * @return 读取到的文本；失败或空流时返回空串
     * @sideeffects 关闭 {@code in}
     */
    private static String readLimited(InputStream in, int max) {
        try {
            byte[] buf = new byte[max];
            int n = in.read(buf);
            if (n <= 0) {
                return "";
            }
            return new String(buf, 0, n, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
            }
        }
    }

    /**
     * 带 HTTP 状态码的运行时异常。
     *
     * <p>供 {@link #isRetryable} 与 {@link #withRetry} 识别可重试的 HTTP 失败。
     */
    public static final class HttpStatusException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        /** HTTP 响应状态码（如 429、503）。 */
        public final int status;

        /**
         * @param status  HTTP 状态码
         * @param message 异常消息（通常含 body 摘要）
         */
        public HttpStatusException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
