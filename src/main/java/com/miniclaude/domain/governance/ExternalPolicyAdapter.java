package com.miniclaude.domain.governance;

import com.miniclaude.domain.runtime.PolicyDecision;
import com.miniclaude.domain.runtime.PolicyRequest;

/**
 * 可选外部策略边界（例如 OPA HTTP）。
 *
 * <p>默认配置不提供实现、不发起网络请求；启用时必须显式提供适配器 Bean。外部决策系统发生
 * 超时、不可达或返回不可解析结果时，适配器应失败关闭，不能把基础设施故障降级成 ALLOW。
 * 该端口也避免领域层依赖具体网络协议。</p>
 */
public interface ExternalPolicyAdapter {
    PolicyDecision evaluate(PolicyRequest request);
}
