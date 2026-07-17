package com.miniclaude.infrastructure.agent;

import com.miniclaude.domain.agent.AgentGateway;
import com.miniclaude.domain.agent.AgentSettings;
import com.miniclaude.domain.agent.ChatTurnResult;
import com.miniclaude.domain.runtime.AgentRuntime;
import com.miniclaude.domain.runtime.AgentRuntimeRequest;
import com.miniclaude.domain.runtime.AgentRuntimeResult;
import com.miniclaude.domain.runtime.ExecutionContext;
import org.springframework.stereotype.Component;

/**
 * Agent 网关基础设施适配器。
 * <p>
 * 将聊天领域端口桥接到稳定的 {@link AgentRuntime}，不感知具体引擎实现。
 */
@Component
public class EngineAgentGateway implements AgentGateway {

    private final AgentRuntime agentRuntime;

    public EngineAgentGateway(AgentRuntime agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    @Override
    public ChatTurnResult chat(
            ExecutionContext context,
            AgentSettings settings,
            String message) {
        AgentRuntimeResult result =
                agentRuntime.execute(new AgentRuntimeRequest(context, settings, message));
        return new ChatTurnResult(
                context.getSessionId(),
                result.getText(),
                result.getTokens(),
                result.getModel());
    }

    @Override
    public void closeSession(ExecutionContext context) {
        agentRuntime.close(context);
    }
}
