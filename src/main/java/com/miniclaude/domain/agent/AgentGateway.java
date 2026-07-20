package com.miniclaude.domain.agent;

import com.miniclaude.domain.runtime.ExecutionContext;

/**
 * Agent 出站端口（Hexagonal Architecture 的 Outbound Port）。
 * <p>
 * <b>为何放在 domain：</b>Chat 用例的核心能力是「在显式安全边界内完成一轮对话」，
 * 领域层只需声明「需要什么能力」，不应依赖 Spring、旧 Agent 引擎或 HTTP SDK。
 * <p>
 * <b>不变量：</b>
 * <ul>
 *   <li>每次 {@link #chat} 必须在给定的 {@link ExecutionContext} 内执行，不得隐式切换租户/会话。</li>
 *   <li>{@link AgentSettings} 为不可变快照；网关实现不得修改传入配置。</li>
 *   <li>删除会话时应用层必须调用 {@link #closeSession}，避免引擎侧资源泄漏。</li>
 * </ul>
 * <p>
 * <b>边界：</b>
 * <ul>
 *   <li><b>application</b>：{@code ChatApplicationService} 组装 context/settings/message 后调用。</li>
 *   <li><b>infrastructure</b>：{@code LegacyAgentRuntime} / 引擎适配器实现本接口，负责 ReAct 循环、工具调用、模型路由。</li>
 * </ul>
 */
public interface AgentGateway {

    /**
     * 执行一轮用户对话，返回助手回复与 token 用量。
     * <p>
     * 前置条件：{@code context} 与 {@code settings} 非空；{@code message} 由应用层校验非空。
     * 副作用：可能触发模型调用、工具执行与计费；同一 message 重放不保证幂等。
     *
     * @param context  显式运行上下文（工作区、租户、会话、运行、追踪 ID）
     * @param settings 本轮运行时配置（模型、权限、成本上限等）
     * @param message  用户输入文本
     * @return 本轮对话结果，含 sessionId、reply、tokens、model
     */
    ChatTurnResult chat(ExecutionContext context, AgentSettings settings, String message);

    /**
     * 释放引擎侧与会话 ID 绑定的资源（内存上下文、临时文件句柄等）。
     * <p>
     * 应用层在删除 {@link com.miniclaude.domain.session.ChatSession} 时应同步调用；
     * 实现应允许对不存在的会话安全 no-op。
     *
     * @param context 待释放资源的执行上下文
     */
    void closeSession(ExecutionContext context);
}
