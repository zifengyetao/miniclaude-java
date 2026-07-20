package com.miniclaude.domain.runtime;

/**
 * 工具及运行行为的策略判定 Outbound Port。
 * <p>
 * <b>为何放在 domain：</b>副作用前必须策略 gate，端口定义判定契约，不绑定 Rego/SQL 规则存储。
 * <p>
 * <b>不变量：</b>
 * <ul>
 *   <li>调用方在工具/写操作<b>之前</b> evaluate；未知 outcome 或异常 → fail-closed（等同 DENY）。</li>
 *   <li>仅 {@link PolicyDecision.Outcome#ALLOW} 可继续自动执行；REQUIRE_APPROVAL 转审批链。</li>
 * </ul>
 * <p>
 * <b>边界：</b>infrastructure 内置引擎 + 可选 {@link com.miniclaude.domain.governance.ExternalPolicyAdapter}。
 */
public interface PolicyEngine {

    /**
     * 对 action+resource 在 context 边界内作策略判定。
     *
     * @param request 含 ExecutionContext、action、resource
     * @return ALLOW / DENY / REQUIRE_APPROVAL
     */
    PolicyDecision evaluate(PolicyRequest request);
}
