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
 * 会话管理 REST 入站适配器。
 * <p>
 * <b>职责</b>：暴露会话 CRUD HTTP 端点，将领域 {@link ChatSession} 映射为 {@link SessionResponse}。
 * <p>
 * <b>上游</b>：客户端对 {@code /api/v1/sessions} 的 REST 调用。
 * <b>下游</b>：{@link SessionApplicationService}；删除时还会触发 {@code AgentGateway} 资源释放。
 * <p>
 * <b>安全/约束</b>：当前无租户隔离，列表返回全部会话；生产环境需叠加认证与租户过滤。
 * 删除为幂等语义由应用层保证（重复 DELETE 不泄漏资源）。
 */
@RestController
@RequestMapping(path = "/api/v1/sessions", produces = MediaType.APPLICATION_JSON_VALUE)
public class SessionController {

    private final SessionApplicationService sessionApplicationService;

    /**
     * @param sessionApplicationService 会话用例服务
     */
    public SessionController(SessionApplicationService sessionApplicationService) {
        this.sessionApplicationService = sessionApplicationService;
    }

    /**
     * 创建新聊天会话。
     *
     * @param request 可选请求体；{@code null} 或空体时使用全局默认模型
     * @return 新会话元数据，HTTP 201
     * @implNote 请求体设为 {@code required = false}，便于客户端发送空 POST 快速建会话
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public SessionResponse create(@RequestBody(required = false) CreateSessionRequest request) {
        String model = request != null ? request.getModel() : null;
        return toDto(sessionApplicationService.create(model));
    }

    /**
     * 列出全部会话（按仓储实现顺序）。
     *
     * @return 会话元数据列表，无分页
     */
    @GetMapping
    public List<SessionResponse> list() {
        return sessionApplicationService.list().stream()
                .map(this::toDto)
                .collect(Collectors.toList());
    }

    /**
     * 按 ID 查询单个会话。
     *
     * @param id 会话唯一标识
     * @return 会话元数据
     * @throws com.miniclaude.application.chat.SessionNotFoundException 不存在时（404）
     */
    @GetMapping("/{id}")
    public SessionResponse get(@PathVariable("id") String id) {
        return toDto(sessionApplicationService.get(id));
    }

    /**
     * 删除会话并释放 Agent 引擎侧关联资源。
     *
     * @param id 会话 ID
     * @return 无响应体，HTTP 204
     * @implNote 先关闭运行时资源再删元数据，顺序由应用层保证
     */
    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable("id") String id) {
        sessionApplicationService.delete(id);
    }

    /**
     * 领域会话对象 → REST 响应 DTO 的私有映射。
     *
     * @param s 领域会话
     * @return 对外可序列化的响应体
     */
    private SessionResponse toDto(ChatSession s) {
        return new SessionResponse(
                s.getId(),
                s.getCreatedAt(),
                s.getLastActiveAt(),
                s.getModel(),
                s.getTitle());
    }
}
