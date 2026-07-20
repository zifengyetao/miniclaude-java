package com.miniclaude.domain.governance;

import com.miniclaude.domain.runtime.PolicyDecision;
import com.miniclaude.domain.runtime.PolicyRequest;

/**
 * 可选外部策略边界 Outbound Port（例如 OPA HTTP）。
 * <p>
 * <b>为何放在 domain：</b>领域需声明「可插拔外部策略源」，但不依赖 HTTP 客户端或 OPA SDK。
 * <p>
 * <b>不变量：</b>超时、不可达、不可解析响应 → fail-closed（DENY 或异常），禁止降级为 ALLOW。
 * <p>
 * <b>边界：</b>默认无 Bean；启用时在 infrastructure 提供 HTTP 适配器，与内置 {@link PolicyRule} 引擎可组合。
 */
public interface ExternalPolicyAdapter {

    /**
     * 对外部策略系统发起评估。
     *
     * @param request 含 ExecutionContext、action、resource
     * @return 与 {@link PolicyDecision} 语义一致的决定
     */
    PolicyDecision evaluate(PolicyRequest request);
}
