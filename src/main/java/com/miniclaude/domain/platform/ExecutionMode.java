package com.miniclaude.domain.platform;

/**
 * 平台统一支持的 Agent 执行模式。
 *
 * <p>该枚举是领域层的能力分类，不等同于具体引擎配置；员工定义通过它显式限制可启动的运行类型。
 */
public enum ExecutionMode {
    CHAT,
    PLAN_EXECUTE,
    GOAL,
    GRAPH
}
