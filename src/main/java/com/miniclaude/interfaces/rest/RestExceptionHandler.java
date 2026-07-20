package com.miniclaude.interfaces.rest;

import com.miniclaude.application.chat.SessionNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.HashMap;
import java.util.Map;

/**
 * REST 全局异常处理器（{@code @RestControllerAdvice}）。
 * <p>
 * <b>职责</b>：将应用层、领域层及框架校验异常统一映射为结构化 JSON 与合适 HTTP 状态码，
 * 避免堆栈或内部细节泄露给客户端。
 * <p>
 * <b>上游</b>：所有 {@code interfaces.rest} 控制器抛出的未捕获异常。
 * <b>下游</b>：HTTP 响应体；500 类错误额外写入 SLF4J 日志供运维排查。
 * <p>
 * <b>安全/约束</b>：{@link SecurityException} 映射 403；通用 {@link Exception} 兜底返回固定文案，
 * 不附带 {@code ex.getMessage()}，防止内部路径/SQL 等信息外泄。
 */
@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    /**
     * 会话不存在 → HTTP 404。
     *
     * @param ex 携带 {@code sessionId} 的应用层异常
     * @return {@code error=session_not_found} 及 sessionId 字段，便于客户端定位
     */
    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(SessionNotFoundException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "session_not_found");
        body.put("message", ex.getMessage());
        body.put("sessionId", ex.getSessionId());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /**
     * 业务/参数非法 → HTTP 400。
     *
     * @param ex 通常来自应用层 {@code IllegalArgumentException}
     * @return {@code error=bad_request} 及可读 message
     */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "bad_request");
        body.put("message", ex.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 安全域策略或职责分离阻断 → HTTP 403。
     *
     * @param ex 监管场景、四眼审批、Git 禁区等抛出的 {@link SecurityException}
     * @return {@code error=policy_forbidden}，不向客户端暴露内部策略规则细节
     */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> forbidden(SecurityException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "policy_forbidden");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /**
     * 运行时前置条件不满足（如缺少 API Key、错误图状态）→ HTTP 503。
     *
     * @param ex {@link IllegalStateException}，表示服务暂不可用而非客户端参数错误
     * @return {@code error=illegal_state}
     * @implNote 使用 503 而非 500，暗示客户端可稍后重试或检查部署配置
     */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> illegalState(IllegalStateException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "illegal_state");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /**
     * Bean Validation（{@code @Valid}）校验失败 → HTTP 400，附带字段级错误映射。
     *
     * @param ex Spring 绑定的校验异常
     * @return {@code error=validation_failed} 及 {@code fields} 字典（字段名 → 默认消息）
     */
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> validation(MethodArgumentNotValidException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "validation_failed");
        Map<String, String> fields = new HashMap<>();
        for (FieldError fe : ex.getBindingResult().getFieldErrors()) {
            fields.put(fe.getField(), fe.getDefaultMessage());
        }
        body.put("fields", fields);
        return ResponseEntity.badRequest().body(body);
    }

    /**
     * 未捕获异常兜底 → HTTP 500。
     *
     * @param ex 任意未分类异常
     * @return 固定通用文案，完整堆栈仅写服务端日志
     * @implNote 必须放在具体 {@code @ExceptionHandler} 之后，作为最低优先级兜底
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> generic(Exception ex) {
        log.error("Unhandled REST error", ex);
        Map<String, Object> body = new HashMap<>();
        body.put("error", "internal_error");
        body.put("message", "An unexpected error occurred");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
