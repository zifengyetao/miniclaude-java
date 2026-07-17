package com.miniclaude.interfaces.rest;

import com.miniclaude.application.session.SessionApplicationService;
import com.miniclaude.domain.session.ChatSession;
import com.miniclaude.interfaces.rest.dto.CreateSessionRequest;
import com.miniclaude.interfaces.rest.dto.SessionResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 会话管理 REST 接口。
 * <p>
 * 提供会话的创建、列表、查询与删除能力。
 */
@RestController
@RequestMapping(path = "/api/v1/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
public class SessionController {

    private final SessionApplicationService sessionApplicationService;

    public SessionController(SessionApplicationService sessionApplicationService) {
        this.sessionApplicationService = sessionApplicationService;
    }

    /**
     * 创建新会话；请求体可省略，模型未传时使用默认配置。
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse create(@RequestBody(required = false) CreateSessionRequest request) {
        String model = request != null ? request.getModel() : null;
        return toDto(sessionApplicationService.create(model));
    }

    /** 列出全部会话。 */
    @GetMapping
    public List<SessionResponse> list() {
        return sessionApplicationService.list().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /** 按 ID 查询单个会话。 */
    @GetMapping("/{id}")
    public SessionResponse get(@PathVariable("id") String id) {
        return toDto(sessionApplicationService.get(id));
    }

    /** 删除会话并释放关联资源。 */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") String id) {
        sessionApplicationService.delete(id);
    }

    /** 领域对象转 REST 响应 DTO。 */
    private SessionResponse toDto(ChatSession s) {
        return new SessionResponse(
                s.getId(),
                s.getCreatedAt(),
                s.getLastActiveAt(),
                s.getModel(),
                s.getTitle());
    }
}
