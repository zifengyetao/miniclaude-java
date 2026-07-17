package com.miniclaude.domain.runtime;

/**
 * 工具及运行行为的策略判定端口。
 */
public interface PolicyEngine {

    PolicyDecision evaluate(PolicyRequest request);
}
