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
 * 聊天领域端口到 {@link AgentRuntime} 的基础设施适配器。
 *
 * <p>本类只完成请求和结果形态转换，不感知具体引擎、会话缓存或工作区实现，也不吞掉
 * 运行时异常。会话隔离、串行化和资源释放由运行时依据 {@link ExecutionContext} 保证。
 */
@Component
public class EngineAgentGateway implements AgentGateway {

    private final AgentRuntime agentRuntime;

    public EngineAgentGateway(AgentRuntime agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    /**
     * 执行一轮聊天并保留原始执行上下文。
     *
     * <p>上下文、设置和消息必须满足运行时请求不变量；运行时失败直接向上传播。
     * 调用可能产生外部副作用，不保证幂等；并发语义完全沿用所注入运行时。
     */
    @Override
    public ChatTurnResult chat(
            ExecutionContext context,
            AgentSettings settings,
            String message) {
        // 不在适配层重建上下文，避免丢失租户、工作区或追踪安全边界。
        AgentRuntimeResult result =
                agentRuntime.execute(new AgentRuntimeRequest(context, settings, message));
        return new ChatTurnResult(
                context.getSessionId(),
                result.getText(),
                result.getTokens(),
                result.getModel());
    }

    /**
     * 将会话关闭转交运行时。空值或关闭失败由运行时定义；适配层不持有额外资源。
     */
    @Override
    public void closeSession(ExecutionContext context) {
        agentRuntime.close(context);
    }
}
