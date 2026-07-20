package com.miniclaude.domain.runtime;

/**
 * Agent 执行运行时 Outbound Port（路径 A/B 的统一执行抽象）。
 * <p>
 * <b>为何放在 domain：</b>应用层需声明「执行一轮 Agent 请求」而不绑定 Legacy 引擎或 Spring。
 * <p>
 * <b>不变量：</b>
 * <ul>
 *   <li>必须按 {@link ExecutionContext} 隔离租户/会话/Run 资源。</li>
 *   <li>{@link #execute} 通常有副作用且不幂等；并发语义由实现定义。</li>
 *   <li>{@link #close} 应幂等，可安全重复调用。</li>
 * </ul>
 * <p>
 * <b>边界：</b>application Chat/Platform 服务调用；infrastructure {@code LegacyAgentRuntime} 实现。
 */
public interface AgentRuntime {

    /**
     * 执行一轮 Agent 请求（Chat ReAct 或场景步进的一次调用）。
     * <p>
     * 前置：request 字段完整有效；策略/审批应在产生副作用前由 application 完成。
     * 故障：模型/工具/运行时错误以 unchecked 异常上报。
     *
     * @param request context + settings + input
     * @return 文本、tokens、model
     */
    AgentRuntimeResult execute(AgentRuntimeRequest request);

    /**
     * 释放 context 关联资源（会话缓存、租约等）。
     *
     * @param context 执行上下文
     */
    void close(ExecutionContext context);
}
