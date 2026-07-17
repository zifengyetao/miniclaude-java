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
 * REST 全局异常处理器。
 * <p>
 * 将应用层与领域层异常统一映射为结构化 JSON 响应与合适的 HTTP 状态码。
 */
@RestControllerAdvice
public class RestExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(RestExceptionHandler.class);

    /** 会话不存在 → 404。 */
    @ExceptionHandler(SessionNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(SessionNotFoundException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "session_not_found");
        body.put("message", ex.getMessage());
        body.put("sessionId", ex.getSessionId());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    /** 参数非法 → 400。 */
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> badRequest(IllegalArgumentException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "bad_request");
        body.put("message", ex.getMessage());
        return ResponseEntity.badRequest().body(body);
    }

    /** 安全域策略或职责分离阻断 → 403。 */
    @ExceptionHandler(SecurityException.class)
    public ResponseEntity<Map<String, Object>> forbidden(SecurityException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "policy_forbidden");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(body);
    }

    /** 运行时前置条件不满足（如缺少 API Key）→ 503。 */
    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> illegalState(IllegalStateException ex) {
        Map<String, Object> body = new HashMap<>();
        body.put("error", "illegal_state");
        body.put("message", ex.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /** Bean Validation 校验失败 → 400，附带字段级错误。 */
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

    /** 未捕获异常 → 500，兜底防止堆栈泄露。 */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> generic(Exception ex) {
        log.error("Unhandled REST error", ex);
        Map<String, Object> body = new HashMap<>();
        body.put("error", "internal_error");
        body.put("message", "An unexpected error occurred");
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }
}
