package com.miniclaude.domain.agent;

import com.miniclaude.domain.runtime.ExecutionContext;

/**
 * Agent 出站端口。
 * <p>
 * 领域层仅通过此接口与底层 Agent 引擎交互，隔离具体实现细节。
 */
public interface AgentGateway {

    /**
     * 执行一轮用户对话，返回助手回复与 token 用量。
     *
     * @param context   显式运行上下文
     * @param settings  本轮运行时配置
     * @param message   用户输入
     */
    ChatTurnResult chat(ExecutionContext context, AgentSettings settings, String message);

    /**
     * 释放引擎侧会话资源；应用层删除会话时应同步调用。
     */
    void closeSession(ExecutionContext context);
}
