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
 * HTTP 客户端与重试工具。
 *
 * <p>职责：封装 {@link java.net.http.HttpClient}，提供 JSON POST 与流式 POST，
 * 并对 429/503/529 等可重试错误执行指数退避重试。
 *
 * <p>在系统中的位置：{@code infrastructure/engine} 层，
 * 供 {@link Agent} 调用 Anthropic / OpenAI 兼容 API 时使用。
 */
public final class ApiHttp {

    private static final HttpClient CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(30))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private ApiHttp() {}

    /** 获取共享的 HttpClient 单例。 */
    public static HttpClient client() {
        return CLIENT;
    }

    /** 判断 HTTP 状态码是否属于可重试范围（429、503、529）。 */
    public static boolean isRetryableStatus(int status) {
        return status == 429 || status == 503 || status == 529;
    }

    /** 判断异常（含嵌套 cause）是否可重试。 */
    public static boolean isRetryable(Throwable error) {
        if (error == null) {
            return false;
        }
        String msg = error.getMessage() != null ? error.getMessage().toLowerCase() : "";
        if (msg.contains("overloaded") || msg.contains("econnreset") || msg.contains("etimedout")
                || msg.contains("connection reset") || msg.contains("timed out")) {
            return true;
        }
        if (error instanceof HttpStatusException) {
            return isRetryableStatus(((HttpStatusException) error).status);
        }
        Throwable cause = error.getCause();
        return cause != null && cause != error && isRetryable(cause);
    }

    /**
     * 对可重试失败执行指数退避重试，最多 {@code maxRetries} 次。
     *
     * @param fn         待执行的 HTTP 调用
     * @param maxRetries 最大重试次数
     */
    public static <T> T withRetry(Supplier<T> fn, int maxRetries) {
        for (int attempt = 0; ; attempt++) {
            try {
                return fn.get();
            } catch (Exception error) {
                if (attempt >= maxRetries || !isRetryable(error)) {
                    if (error instanceof RuntimeException) {
                        throw (RuntimeException) error;
                    }
                    throw new RuntimeException(error);
                }
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
     * 发送 JSON POST 请求并返回流式响应体（用于 SSE 流）。
     */
    public static HttpResponse<InputStream> postJsonStream(
            String url, String body, Map<String, String> headers) throws IOException, InterruptedException {
        HttpRequest.Builder b = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (headers != null) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                if (e.getValue() != null) {
                    b.header(e.getKey(), e.getValue());
                }
            }
        }
        HttpResponse<InputStream> resp = CLIENT.send(b.build(), HttpResponse.BodyHandlers.ofInputStream());
        if (isRetryableStatus(resp.statusCode())) {
            try {
                resp.body().close();
            } catch (Exception ignored) {
            }
            throw new HttpStatusException(resp.statusCode(), "HTTP " + resp.statusCode());
        }
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            String errBody = readLimited(resp.body(), 4000);
            throw new HttpStatusException(resp.statusCode(),
                    "HTTP " + resp.statusCode() + ": " + errBody);
        }
        return resp;
    }

    /** 发送 JSON POST 请求并返回完整响应字符串。 */
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

    /** 带 HTTP 状态码的运行时异常，用于重试判断。 */
    public static final class HttpStatusException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        public final int status;

        public HttpStatusException(int status, String message) {
            super(message);
            this.status = status;
        }
    }
}
