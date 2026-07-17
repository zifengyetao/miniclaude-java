package com.miniclaude.domain.runtime;

/**
 * Agent 执行运行时端口。
 */
public interface AgentRuntime {

    AgentRuntimeResult execute(AgentRuntimeRequest request);

    void close(ExecutionContext context);
}
