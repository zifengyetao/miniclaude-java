package com.miniclaude.interfaces.rest;

import com.miniclaude.application.chat.ChatApplicationService;
import com.miniclaude.application.chat.ChatCommand;
import com.miniclaude.domain.agent.ChatTurnResult;
import com.miniclaude.interfaces.rest.dto.ChatRequest;
import com.miniclaude.interfaces.rest.dto.ChatResponse;
import org.springframework.http.MediaType;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 聊天 REST 入站适配器（接口层）。
 * <p>
 * <b>职责</b>：接收 HTTP JSON 请求，校验后转换为 {@link ChatCommand}，调用应用服务并映射为
 * {@link ChatResponse}；不持有会话状态，也不直接调用 LLM 网关。
 * <p>
 * <b>上游</b>：前端或 API 客户端 POST {@code /api/v1/chat}。
 * <b>下游</b>：{@link ChatApplicationService} 编排会话与 {@code AgentGateway}。
 * <p>
 * <b>安全/约束</b>：消息必填由 Bean Validation 保证；租户/身份当前使用应用层默认租户，
 * 不在此控制器解析用户声明的租户字段；异常由 {@link RestExceptionHandler} 统一映射。
 */
@RestController
@RequestMapping(path = "/api/v1/chat", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class ChatController {

    private final ChatApplicationService chatApplicationService;

    /**
     * 构造注入聊天应用服务。
     *
     * @param chatApplicationService 聊天用例协调器，由 Spring 容器提供
     */
    public ChatController(ChatApplicationService chatApplicationService) {
        this.chatApplicationService = chatApplicationService;
    }

    /**
     * 发送一条用户消息并获取助手回复。
     *
     * @param request 聊天请求体；{@code sessionId} 省略时服务端隐式创建新会话
     * @return 包含会话 ID、回复文本、实际模型及 token 用量的响应 DTO
     * @throws javax.validation.ValidationException 请求体校验失败（由全局处理器映射为 400）
     * @throws com.miniclaude.application.chat.SessionNotFoundException 指定 sessionId 不存在（404）
     * @throws IllegalArgumentException 消息为空等业务前置条件不满足（400）
     * @throws IllegalStateException 运行时未配置 API Key 等（503）
     * @implNote 控制器只做 DTO→Command→DTO 转换，模型/轮次覆盖逻辑委托应用层，
     *           避免接口层重复实现会话规则
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        // 将 REST 入参封装为不可变命令对象，隔离 HTTP 层与用例层
        ChatTurnResult result = chatApplicationService.chat(new ChatCommand(
                request.getSessionId(),
                request.getMessage(),
                request.getModel(),
                request.getMaxTurns()));
        // 领域结果 → 对外 DTO，不暴露内部 token 结构以外的运行时细节
        return new ChatResponse(
                result.getSessionId(),
                result.getReply(),
                result.getModel(),
                result.getTokens());
    }
}
