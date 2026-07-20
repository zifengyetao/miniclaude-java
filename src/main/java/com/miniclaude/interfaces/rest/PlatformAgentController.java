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
 * 数字员工（Agent 定义）REST 入站适配器。
 *
 * <p><b>职责</b>：HTTP 路由、Bean Validation 与 HTTP 状态码；不承载领域不变量或事务。
 * <p><b>上游</b>：平台管理 UI / API 客户端。
 * <b>下游</b>：{@link AgentPlatformService}；鉴权由安全过滤器承担（本控制器未显式校验租户）。
 * <p><b>安全/约束</b>：响应当前直接序列化不可变 {@link AgentDefinition} 领域对象；
 * 创建为非幂等端点，重复 POST 会产生多条草稿定义。
 */
@RestController
@RequestMapping("/api/v1/platform/agents")
public class PlatformAgentController {

    private final AgentPlatformService platform;

    /**
     * @param platform 数字员工定义与运行的应用服务
     */
    public PlatformAgentController(AgentPlatformService platform) {
        this.platform = platform;
    }

    /**
     * 列出全部 Agent 定义（含草稿与已发布，取决于仓储内容）。
     *
     * @return 定义列表，无分页
     */
    @GetMapping
    public List<AgentDefinition> list() {
        return platform.listAgents();
    }

    /**
     * 按 ID 获取单个 Agent 定义。
     *
     * @param id 定义主键
     * @return 完整定义快照
     * @throws IllegalArgumentException 不存在时（400）
     */
    @GetMapping("/{id}")
    public AgentDefinition get(@PathVariable String id) {
        return platform.getAgent(id);
    }

    /**
     * 创建新的草稿 Agent 定义。
     *
     * @param request 经 {@code @Valid} 校验的创建请求
     * @return 持久化后的定义，HTTP 201
     * @throws IllegalArgumentException 领域组合非法或持久化失败（400，事务回滚）
     * @implNote 非幂等；并发相同请求会创建不同 ID 的定义
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
