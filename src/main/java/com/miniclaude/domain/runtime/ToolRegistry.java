package com.miniclaude.domain.runtime;

/** Harness 工具注册控制面端口；实现必须拒绝同名覆盖。 */
public interface ToolRegistry {
    void register(String toolName, ToolGateway gateway);
}
