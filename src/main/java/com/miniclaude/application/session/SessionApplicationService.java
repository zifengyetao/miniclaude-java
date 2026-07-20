package com.miniclaude.application.session;

import com.miniclaude.application.chat.SessionNotFoundException;
import com.miniclaude.domain.agent.AgentGateway;
import com.miniclaude.domain.agent.AgentSettings;
import com.miniclaude.domain.runtime.ExecutionContext;
import com.miniclaude.domain.session.ChatSession;
import com.miniclaude.domain.session.SessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.UUID;

/**
 * 会话管理应用服务。
 * <p>
 * <b>职责</b>：会话 CRUD，与 {@link com.miniclaude.application.chat.ChatApplicationService} 解耦，
 * 支持独立 {@code /api/v1/sessions} REST 端点。
 * <p>
 * <b>上游</b>：{@link com.miniclaude.interfaces.rest.SessionController}。
 * <b>下游</b>：{@link SessionRepository}、{@link AgentGateway}（删除时释放资源）。
 * <p>
 * <b>安全/约束</b>：当前无租户隔离；删除须先 closeSession 再删元数据，顺序不可颠倒。
 */
@Service
public class SessionApplicationService {

    private static final String DEFAULT_TENANT = "default";

    private final SessionRepository sessionRepository;
    private final AgentGateway agentGateway;
    private final AgentSettings defaultSettings;

    public SessionApplicationService(
            SessionRepository sessionRepository,
            AgentGateway agentGateway,
            AgentSettings defaultSettings) {
        this.sessionRepository = sessionRepository;
        this.agentGateway = agentGateway;
        this.defaultSettings = defaultSettings;
    }

    /**
     * 创建新会话；未指定模型时使用全局默认模型。
     *
     * @param model 可 null/空白，回退 {@code defaultSettings.getModel()}
     * @return 已持久化的新会话
     * @implNote 副作用：写入 {@link SessionRepository}
     */
    public ChatSession create(String model) {
        String m = StringUtils.hasText(model) ? model : defaultSettings.getModel();
        ChatSession session = ChatSession.create(m);
        return sessionRepository.save(session);
    }

    /**
     * 按 ID 获取会话。
     *
     * @throws SessionNotFoundException 会话不存在时
     */
    public ChatSession get(String id) {
        return sessionRepository.findById(id)
                .orElseThrow(() -> new SessionNotFoundException(id));
    }

    /**
     * 返回全部会话列表（无分页、无租户过滤）。
     *
     * @return 仓储中全部会话的快照
     */
    public List<ChatSession> list() {
        return sessionRepository.findAll();
    }

    /**
     * 删除会话并释放引擎侧资源。
     *
     * @param id 会话 ID
     * @implNote 副作用：closeSession + delete；closeSession 应幂等以支持重复 DELETE
     */
    public void delete(String id) {
        /*
         * 必须先通知运行时释放模型/MCP/线程等会话资源，再删除元数据。反向执行会
         * 失去构造上下文所需的 session 标识；即使底层会话不存在，closeSession
         * 也应保持幂等，确保重复 DELETE 不产生资源泄漏。
         */
        agentGateway.closeSession(newExecutionContext(id));
        sessionRepository.delete(id);
    }

    /**
     * 为会话管理动作创建最小执行上下文。
     *
     * <p>删除并不是一次业务推理 Run，但仍分配独立 runId/traceId，使资源释放动作
     * 可以进入统一审计链。默认租户只是当前兼容层约定，后续应由认证上下文替换。
     */
    private ExecutionContext newExecutionContext(String sessionId) {
        Path workspace = StringUtils.hasText(defaultSettings.getWorkingDirectory())
                ? Paths.get(defaultSettings.getWorkingDirectory())
                : Paths.get("");
        return new ExecutionContext(
                workspace,
                DEFAULT_TENANT,
                sessionId,
                UUID.randomUUID().toString(),
                UUID.randomUUID().toString());
    }
}
