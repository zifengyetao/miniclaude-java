package com.miniclaude.domain.runtime;

/**
 * 工具执行 Outbound Port。
 * <p>
 * <b>为何放在 domain：</b>Agent 工具循环需统一 execute 契约，不绑定具体 bash/read/write 实现。
 * <p>
 * <b>不变量：</b>实现须落实白名单、沙箱、策略；工具副作用通常<b>不可逆、不幂等</b>。
 * <p>
 * <b>边界：</b>infrastructure 引擎内工具注册表实现；Coding 场景经 Scenario 端口间接使用。
 */
public interface ToolGateway {

    /**
     * 执行命名工具。
     *
     * @param request context + toolName + arguments
     * @return 成功标志与输出文本
     */
    ToolResult execute(ToolRequest request);
}
