package com.miniclaude.domain.runtime;

/**
 * 工具执行出站端口。
 *
 * <p>实现负责按执行上下文落实工具白名单、权限与沙箱约束。工具可能产生不可逆副作用，
 * 因而调用不默认可重试或幂等。
 */
public interface ToolGateway {

    ToolResult execute(ToolRequest request);
}
