package com.miniclaude.domain.runtime;

/**
 * 工具执行出站端口。
 */
public interface ToolGateway {

    ToolResult execute(ToolRequest request);
}
