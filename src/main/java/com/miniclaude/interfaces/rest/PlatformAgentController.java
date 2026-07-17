package com.miniclaude.interfaces.rest;

import com.miniclaude.application.platform.AgentPlatformService;
import com.miniclaude.domain.platform.AgentDefinition;
import com.miniclaude.interfaces.rest.dto.CreateAgentDefinitionRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 数字员工定义的 REST 入站适配器。
 *
 * <p>负责 HTTP 路由、请求校验和状态码，不承载领域规则、鉴权或事务；这些职责分别位于
 * 安全过滤器和 {@link AgentPlatformService}。响应当前直接使用不可变领域对象。
 */
@RestController
@RequestMapping("/api/v1/platform/agents")
public class PlatformAgentController {

    private final AgentPlatformService platform;

    public PlatformAgentController(AgentPlatformService platform) {
        this.platform = platform;
    }

    @GetMapping
    public List<AgentDefinition> list() {
        return platform.listAgents();
    }

    @GetMapping("/{id}")
    public AgentDefinition get(@PathVariable String id) {
        return platform.getAgent(id);
    }

    /**
     * 创建草稿定义。请求必须通过 Bean Validation；领域或持久化失败由统一异常处理器映射。
     * 该端点非幂等，并发相同请求会创建不同定义。
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentDefinition create(@Valid @RequestBody CreateAgentDefinitionRequest request) {
        return platform.createAgent(
                request.getName(),
                request.getDescription(),
                request.getRoleName(),
                request.getRiskLevel(),
                request.getEvolutionLevel(),
                request.getExecutionModes());
    }
}
