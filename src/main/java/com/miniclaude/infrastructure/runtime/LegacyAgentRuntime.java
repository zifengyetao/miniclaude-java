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

    @Override
    public AgentRuntimeResult execute(AgentRuntimeRequest request) {
        Objects.requireNonNull(request, "request");
        ExecutionContext context = request.getContext();
        AgentSettings settings = request.getSettings();
        validate(settings, context);

        SessionKey key = SessionKey.from(context);
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

    @Override
    public void close(ExecutionContext context) {
        Objects.requireNonNull(context, "context");
        LegacyAgentSession session = sessions.remove(SessionKey.from(context));
        closeQuietly(context.getSessionId(), session);
    }

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
