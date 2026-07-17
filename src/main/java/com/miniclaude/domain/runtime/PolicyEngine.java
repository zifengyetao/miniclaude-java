package com.miniclaude.domain.runtime;

/**
 * 工具及运行行为的策略判定端口。
 *
 * <p>调用方必须在产生副作用前完成判定，并对异常或无法识别的结果采取拒绝策略。
 * 实现应保持判定可审计；是否缓存及并发一致性由具体策略后端决定。
 */
public interface PolicyEngine {

    PolicyDecision evaluate(PolicyRequest request);
}
