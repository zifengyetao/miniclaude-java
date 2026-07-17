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
 * 聊天 REST 接口（接口层）。
 * <p>
 * 接收 HTTP 请求，转换为应用命令并返回对话结果 DTO。
 */
@RestController
@RequestMapping(path = "/api/v1/chat", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class ChatController {

    private final ChatApplicationService chatApplicationService;

    public ChatController(ChatApplicationService chatApplicationService) {
        this.chatApplicationService = chatApplicationService;
    }

    /**
     * 发送一条聊天消息；可续用已有会话或隐式创建新会话。
     */
    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ChatResponse chat(@Valid @RequestBody ChatRequest request) {
        ChatTurnResult result = chatApplicationService.chat(new ChatCommand(
                request.getSessionId(),
                request.getMessage(),
                request.getModel(),
                request.getMaxTurns()));
        return new ChatResponse(
                result.getSessionId(),
                result.getReply(),
                result.getModel(),
                result.getTokens());
    }
}
