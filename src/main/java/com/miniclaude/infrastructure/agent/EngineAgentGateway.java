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
 * 聊天领域端口（{@link AgentGateway}）到运行时端口（{@link AgentRuntime}）的基础设施适配器。
 *
 * <p><b>六边形架构中的位置</b>：{@code application/chat} 只依赖 {@link AgentGateway} 接口；
 * 本类是唯一把 Chat 链路接到 {@link LegacyAgentRuntime}（旧 {@code Agent} 引擎）的桥接层。
 * 应用层因此不 import {@code infrastructure.engine} 包。</p>
 *
 * <p><b>职责边界（刻意保持「薄」）</b>：
 * <ul>
 *   <li>做：{@link AgentRuntimeRequest} / {@link ChatTurnResult} 的形态转换</li>
 *   <li>不做：会话缓存、工作区校验、API Key 解析、工具/模型路由——全部委托运行时</li>
 *   <li>不吞异常：引擎/网关失败原样向上传播，便于 REST 层返回 5xx 或业务错误</li>
 * </ul></p>
 *
 * <p><b>为何不在这里重建 {@link ExecutionContext}</b>：上下文携带 tenantId、workspace、
 * traceId 等安全边界；若在适配层丢弃或覆盖字段，下游策略与审计将无法关联请求。</p>
 */
@Component
public class EngineAgentGateway implements AgentGateway {

    /** 注入的运行时实现，通常为 {@link com.miniclaude.infrastructure.runtime.LegacyAgentRuntime} */
    private final AgentRuntime agentRuntime;

    /**
     * 构造网关，绑定具体运行时 Bean。
     *
     * @param agentRuntime Spring 容器中的 {@link AgentRuntime} 唯一实现
     */
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
