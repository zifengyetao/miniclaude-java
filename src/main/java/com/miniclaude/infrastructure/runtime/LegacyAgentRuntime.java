package com.miniclaude.infrastructure.runtime;

import com.miniclaude.domain.agent.AgentSettings;
import com.miniclaude.domain.runtime.AgentRuntime;
import com.miniclaude.domain.runtime.AgentRuntimeRequest;
import com.miniclaude.domain.runtime.AgentRuntimeResult;
import com.miniclaude.domain.runtime.ExecutionContext;
import com.miniclaude.infrastructure.engine.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 旧版集中式 {@link Agent} 到新运行时端口的隔离适配器。
 *
 * <p>旧引擎仍依赖进程工作目录，因此仅允许与进程目录相同的显式 workspace；
 * 在引擎完成工作区隔离前，其他目录一律拒绝，且不会修改 {@code user.dir}。
 * 适配器按工作区、租户和会话缓存旧引擎实例；同一会话串行执行，不同会话可并发。
 */
@Component
public class LegacyAgentRuntime implements AgentRuntime {

    private static final Logger log = LoggerFactory.getLogger(LegacyAgentRuntime.class);

    private final ConcurrentHashMap<SessionKey, LegacyAgentSession> sessions =
            new ConcurrentHashMap<>();
    private final LegacyAgentFactory agentFactory;

    public LegacyAgentRuntime() {
        this(LegacyAgentRuntime::createAgentSession);
    }

    LegacyAgentRuntime(LegacyAgentFactory agentFactory) {
        this.agentFactory = Objects.requireNonNull(agentFactory, "agentFactory");
    }

    /**
     * 在通过 API 密钥和工作区边界校验后执行一轮旧引擎会话。
     *
     * <p>请求必须非空，工作区必须存在且与进程工作区的真实路径一致。缺失密钥、越界路径、
     * 工厂或旧引擎异常均向上传播；调用可能产生工具副作用，非幂等。同一会话通过实例锁
     * 串行化，以保护旧引擎内部可变状态。
     */
    @Override
    public AgentRuntimeResult execute(AgentRuntimeRequest request) {
        Objects.requireNonNull(request, "request");
        ExecutionContext context = request.getContext();
        AgentSettings settings = request.getSettings();
        validate(settings, context);

        SessionKey key = SessionKey.from(context);
        // 原子地为会话创建唯一实例，避免并发首轮请求各自持有一份不一致状态。
        LegacyAgentSession session = sessions.computeIfAbsent(
                key, ignored -> agentFactory.create(settings));
        Map<String, Object> raw;
        synchronized (session) {
            raw = session.runOnce(request.getInput());
        }
        if (raw == null) {
            throw new IllegalStateException("Legacy Agent returned no result");
        }

        String text = raw.get("text") != null ? String.valueOf(raw.get("text")) : "";
        Map<String, Integer> tokens = tokenCounts(raw.get("tokens"));
        return new AgentRuntimeResult(text, tokens, settings.getModel());
    }

    /**
     * 移除并关闭上下文对应的会话。不存在的会话会被忽略，重复调用不会重复关闭；
     * 与执行并发时，仅保证映射移除的原子性，调用方应先停止新请求。
     */
    @Override
    public void close(ExecutionContext context) {
        Objects.requireNonNull(context, "context");
        LegacyAgentSession session = sessions.remove(SessionKey.from(context));
        closeQuietly(context.getSessionId(), session);
    }

    /** 容器停止时尽力关闭所有残留会话；单个关闭失败不会阻断其余资源释放。 */
    @PreDestroy
    public void shutdown() {
        sessions.forEach((key, session) -> closeQuietly(key.sessionId, session));
        sessions.clear();
    }

    private static void validate(AgentSettings settings, ExecutionContext context) {
        if (!StringUtils.hasText(settings.getApiKey())) {
            throw new IllegalStateException(
                    "API key is required. Set MOONSHOT_API_KEY / OPENAI_API_KEY / ANTHROPIC_API_KEY "
                            + "or miniclaude.api-key");
        }
        try {
            // 比较真实路径而非词法路径，防止通过 .. 或符号链接绕过旧引擎的单工作区限制。
            Path processWorkspace = Paths.get("").toAbsolutePath().normalize().toRealPath();
            Path requestedWorkspace = context.getWorkspace().toRealPath();
            if (!processWorkspace.equals(requestedWorkspace)) {
                throw new IllegalArgumentException(
                        "Per-session workspace is disabled in HTTP mode until isolated workspaces are enabled");
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("workspace must be an existing accessible directory", e);
        }
    }

    private static LegacyAgentSession createAgentSession(AgentSettings settings) {
        Agent.Builder builder = Agent.builder()
                .permissionMode(settings.getPermissionMode())
                .model(settings.getModel())
                .thinking(settings.isThinking())
                .apiKey(settings.getApiKey())
                // HTTP 边界无法安全进行交互式确认，任何需要确认的高风险动作都失败关闭。
                .confirmFn(message -> false);

        if (settings.getMaxCostUsd() != null) {
            builder.maxCostUsd(settings.getMaxCostUsd());
        }
        if (settings.getMaxTurns() != null) {
            builder.maxTurns(settings.getMaxTurns());
        }
        if (settings.isUseOpenAiCompatible() && StringUtils.hasText(settings.getApiBase())) {
            builder.apiBase(settings.getApiBase());
        } else if (StringUtils.hasText(settings.getApiBase())) {
            builder.anthropicBaseUrl(settings.getApiBase());
        }

        Agent agent = builder.build();
        return new LegacyAgentSession() {
            @Override
            public Map<String, Object> runOnce(String input) {
                return agent.runOnce(input);
            }

            @Override
            public void close() {
                agent.close();
            }
        };
    }

    private static Map<String, Integer> tokenCounts(Object rawTokens) {
        Map<String, Integer> tokens = new HashMap<>();
        if (rawTokens instanceof Map) {
            Map<?, ?> values = (Map<?, ?>) rawTokens;
            tokens.put("input", toInt(values.get("input")));
            tokens.put("output", toInt(values.get("output")));
        }
        return tokens;
    }

    private static int toInt(Object value) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            // 旧引擎统计是非强类型数据；无法解释时按零处理，避免污染业务结果。
            return 0;
        }
    }

    private static void closeQuietly(String sessionId, LegacyAgentSession session) {
        if (session == null) {
            return;
        }
        try {
            session.close();
        } catch (Exception e) {
            log.warn("Failed to close legacy agent session {}: {}", sessionId, e.getMessage());
        }
    }

    interface LegacyAgentFactory {
        LegacyAgentSession create(AgentSettings settings);
    }

    interface LegacyAgentSession {
        Map<String, Object> runOnce(String input);

        void close();
    }

    private static final class SessionKey {
        private final Path workspace;
        private final String tenantId;
        private final String sessionId;

        private SessionKey(Path workspace, String tenantId, String sessionId) {
            this.workspace = workspace;
            this.tenantId = tenantId;
            this.sessionId = sessionId;
        }

        private static SessionKey from(ExecutionContext context) {
            return new SessionKey(
                    context.getWorkspace(), context.getTenantId(), context.getSessionId());
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof SessionKey)) {
                return false;
            }
            SessionKey that = (SessionKey) other;
            return workspace.equals(that.workspace)
                    && tenantId.equals(that.tenantId)
                    && sessionId.equals(that.sessionId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(workspace, tenantId, sessionId);
        }
    }
}
