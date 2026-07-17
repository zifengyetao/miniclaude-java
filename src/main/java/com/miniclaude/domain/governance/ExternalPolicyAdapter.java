package com.miniclaude.domain.governance;

import com.miniclaude.domain.runtime.PolicyDecision;
import com.miniclaude.domain.runtime.PolicyRequest;

/**
 * 可选外部策略边界（例如 OPA HTTP）。
 * 默认配置不提供实现、不发起网络请求；启用时必须显式提供适配器 Bean。
 */
public interface ExternalPolicyAdapter {
    PolicyDecision evaluate(PolicyRequest request);
}
