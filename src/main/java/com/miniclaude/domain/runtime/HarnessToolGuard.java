package com.miniclaude.domain.runtime;

import java.util.Set;

/**
 * Profile 级确定性工具前置校验。
 *
 * <p>用于检查参数和调用顺序；通过后仍必须进入租户 PolicyEngine，不能替代授权。</p>
 */
public interface HarnessToolGuard {
    PolicyDecision evaluate(HarnessProfile profile, HarnessModelGateway.ToolCall call,
                            Set<String> successfulTools);
}
