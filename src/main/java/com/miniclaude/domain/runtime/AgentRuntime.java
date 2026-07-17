package com.miniclaude.domain.runtime;

/**
 * Agent 执行运行时端口。
 *
 * <p>领域和应用层只依赖此接口，不感知旧引擎、模型 SDK 或会话缓存。实现必须按
 * {@link ExecutionContext} 隔离租户与会话资源，并明确处理并发执行与关闭之间的竞争。
 */
public interface AgentRuntime {

    /**
     * 执行一轮请求。请求必须完整有效；模型、工具或运行时故障由实现以运行时异常报告。
     * 此操作通常会产生外部副作用且不保证幂等，并发语义由实现负责。
     */
    AgentRuntimeResult execute(AgentRuntimeRequest request);

    /**
     * 释放上下文关联资源。实现应允许重复关闭或安全忽略不存在的会话。
     */
    void close(ExecutionContext context);
}
