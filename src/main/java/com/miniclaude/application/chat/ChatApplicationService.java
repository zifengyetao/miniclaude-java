package com.miniclaude.application.chat;

import com.miniclaude.domain.agent.AgentGateway;
import com.miniclaude.domain.agent.AgentSettings;
import com.miniclaude.domain.agent.ChatTurnResult;
import com.miniclaude.domain.runtime.ExecutionContext;
import com.miniclaude.domain.session.ChatSession;
import com.miniclaude.domain.session.SessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

/**
 * 聊天应用服务。
 * <p>
 * 编排会话生命周期与 Agent 网关调用，是聊天用例的核心协调层。
 */
@Service
public class ChatApplicationService {

    private static final String DEFAULT_TENANT = "default";

    private final AgentGateway agentGateway;
    private final SessionRepository sessionRepository;
    private final AgentSettings defaultSettings;

    public ChatApplicationService(
            AgentGateway agentGateway,
            SessionRepository sessionRepository,
            AgentSettings defaultSettings) {
        this.agentGateway = agentGateway;
        this.sessionRepository = sessionRepository;
        this.defaultSettings = defaultSettings;
    }

    /**
     * 处理一轮聊天：解析/创建会话、更新元数据，再委托 Agent 执行推理。
     *
     * @throws IllegalArgumentException 消息为空时
     * @throws SessionNotFoundException 指定 sessionId 不存在时
     */
    public ChatTurnResult chat(ChatCommand command) {
        if (command == null || !StringUtils.hasText(command.getMessage())) {
            throw new IllegalArgumentException("message is required");
        }

        ChatSession session;
        if (StringUtils.hasText(command.getSessionId())) {
            session = sessionRepository.findById(command.getSessionId())
                    .orElseThrow(() -> new SessionNotFoundException(command.getSessionId()));
        } else {
            String model = StringUtils.hasText(command.getModel())
                    ? command.getModel()
                    : defaultSettings.getModel();
            session = ChatSession.create(model);
            sessionRepository.save(session);
        }

        if (StringUtils.hasText(command.getModel())) {
            session.setModel(command.getModel());
        }
        if (session.getTitle() == null || session.getTitle().isEmpty()) {
            String title = command.getMessage().trim();
            if (title.length() > 40) {
                title = title.substring(0, 40) + "…";
            }
            session.rename(title);
        }
        session.touch();
        sessionRepository.save(session);

        AgentSettings settings = defaultSettings.withSessionOverrides(
                session.getModel(), command.getMaxTurns());
        ExecutionContext context = newExecutionContext(session.getId());

        return agentGateway.chat(context, settings, command.getMessage().trim());
    }

    /**
     * 关闭会话：释放引擎资源并删除持久化记录。
     */
    public void closeSession(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        agentGateway.closeSession(newExecutionContext(sessionId));
        sessionRepository.delete(sessionId);
    }

    private ExecutionContext newExecutionContext(String sessionId) {
        Path workspace = StringUtils.hasText(defaultSettings.getWorkingDirectory())
                ? Paths.get(defaultSettings.getWorkingDirectory())
                : Paths.get("");
        String runId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        return new ExecutionContext(workspace, DEFAULT_TENANT, sessionId, runId, traceId);
    }
}
