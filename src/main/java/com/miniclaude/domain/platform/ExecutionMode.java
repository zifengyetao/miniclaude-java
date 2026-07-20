package com.miniclaude.domain.platform;

/**
 * 平台统一支持的 Agent 执行模式（领域能力分类枚举）。
 * <p>
 * <b>为何放在 domain：</b>数字员工定义与 Run 快照需声明「允许以何种模式启动」，
 * 这是业务语义而非引擎配置细节。
 * <p>
 * <b>不变量：</b>{@link com.miniclaude.domain.platform.AgentDefinition} 的
 * {@code executionModes} 至少包含一项；Run 的 {@code executionMode} 必须属于定义允许集合
 * （由 application 层校验）。
 * <p>
 * <b>边界：</b>application 启动 Run 时选择；infrastructure 编排器按模式分支步进逻辑。
 */
public enum ExecutionMode {
    /** 通用对话：单轮/多轮 Chat，走 {@link com.miniclaude.domain.agent.AgentGateway} 路径。 */
    CHAT,
    /** 计划-执行：先规划步骤再逐步执行，适用于复杂任务分解。 */
    PLAN_EXECUTE,
    /** 目标驱动：以 goal 为终态，运行时自主规划子目标（受 maxSteps/成本约束）。 */
    GOAL,
    /** 图编排：按 {@link com.miniclaude.domain.graph.GraphSpec} 节点与边流转执行。 */
    GRAPH
}
