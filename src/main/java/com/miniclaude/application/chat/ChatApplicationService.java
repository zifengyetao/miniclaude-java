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
 * 聊天应用服务（用例协调层）。
 * <p>
 * <b>职责</b>：解析/创建会话、更新元数据、构造 {@link ExecutionContext}，并委托
 * {@link AgentGateway} 执行单轮推理。
 * <p>
 * <b>上游</b>：{@link com.miniclaude.interfaces.rest.ChatController} 经 {@link ChatCommand} 调用。
 * <b>下游</b>：{@link SessionRepository}、{@link AgentGateway}、{@link AgentSettings}。
 * <p>
 * <b>安全/约束</b>：HTTP 兼容层固定 {@code DEFAULT_TENANT}；真实租户须由认证上下文注入，
 * 不可信任用户消息中的租户声明。每条网关调用生成独立 runId/traceId 便于审计。
 */
@Service
public class ChatApplicationService {

    /** 当前 REST 兼容层的默认租户标识，待认证接入后替换。 */
    private static final String DEFAULT_TENANT = "default";

    private final AgentGateway agentGateway;
    private final SessionRepository sessionRepository;
    private final AgentSettings defaultSettings;

    /**
     * @param agentGateway       LLM/Agent 运行时网关
     * @param sessionRepository  会话持久化
     * @param defaultSettings    全局默认模型、工作目录等
     */
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
     * @param command 不可为 null，且 message 须有文本
     * @return 含 sessionId、reply、model、tokens 的单轮结果
     * @throws IllegalArgumentException 消息为空时
     * @throws SessionNotFoundException 指定 sessionId 不存在时
     * @implNote 副作用：可能创建/更新会话持久化记录；调用 Agent 网关（外部 API 费用）
     */
    public ChatTurnResult chat(ChatCommand command) {
        if (command == null || !StringUtils.hasText(command.getMessage())) {
            throw new IllegalArgumentException("message is required");
        }

        ChatSession session;
        // 分支：续用已有会话 vs 隐式创建新会话
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

        // 允许本轮请求覆盖会话绑定模型
        if (StringUtils.hasText(command.getModel())) {
            session.setModel(command.getModel());
        }
        // 首条消息自动生成会话标题（截断至 40 字符）
        if (session.getTitle() == null || session.getTitle().isEmpty()) {
            String title = command.getMessage().trim();
            if (title.length() > 40) {
                title = title.substring(0, 40) + "…";
            }
            session.rename(title);
        }
        session.touch();
        sessionRepository.save(session);

        /*
         * 先冻结本轮配置快照，再构造显式 ExecutionContext。旧引擎曾依赖 JVM 全局
         * user.dir；这里改为逐请求传递 workspace/tenant/session/run/trace，避免并发
         * 会话相互污染，也让后续审计和分布式 Worker 能准确关联同一次执行。
         */
        AgentSettings settings = defaultSettings.withSessionOverrides(
                session.getModel(), command.getMaxTurns());
        ExecutionContext context = newExecutionContext(session.getId());

        return agentGateway.chat(context, settings, command.getMessage().trim());
    }

    /**
     * 关闭会话：释放引擎资源并删除持久化记录。
     *
     * @param sessionId 为空时静默 no-op
     * @implNote 副作用：{@code agentGateway.closeSession} + 仓储 delete；供内部或其他用例调用
     */
    public void closeSession(String sessionId) {
        if (!StringUtils.hasText(sessionId)) {
            return;
        }
        agentGateway.closeSession(newExecutionContext(sessionId));
        sessionRepository.delete(sessionId);
    }

    /**
     * 为一次网关调用创建不可复用的执行上下文。
     *
     * <p>sessionId 负责多轮对话关联；runId 和 traceId 每次调用重新生成，分别用于
     * 业务运行与可观测链路。当前 HTTP 兼容层使用默认租户，真正的身份接入后应由
     * 已认证主体提供 tenant，而不能接受用户消息中的租户声明。
     */
    private ExecutionContext newExecutionContext(String sessionId) {
        Path workspace = StringUtils.hasText(defaultSettings.getWorkingDirectory())
                ? Paths.get(defaultSettings.getWorkingDirectory())
                : Paths.get("");
        String runId = UUID.randomUUID().toString();
        String traceId = UUID.randomUUID().toString();
        return new ExecutionContext(workspace, DEFAULT_TENANT, sessionId, runId, traceId);
    }
}
