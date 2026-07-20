package com.miniclaude.domain.governance;

/**
 * 确定性策略引擎使用的版本化规则值对象。
 * <p>
 * <b>为何放在 domain：</b>deny-first 规则匹配是 Harness 控制面核心，与 Rego/OPA 语法解耦。
 * <p>
 * <b>不变量：</b>
 * <ul>
 *   <li>{@code priority} 仅决定排序与同优先级冲突检测；高优先级 ALLOW 不能覆盖任意 DENY（deny-first）。</li>
 *   <li>无匹配或同优先级效果冲突 → fail-closed（DENY）。</li>
 * </ul>
 * <p>
 * <b>边界：</b>infrastructure 策略引擎加载 JDBC/资产内容；{@link ExternalPolicyAdapter} 为可选补充。
 */
public final class PolicyRule {

    /**
     * 规则效果。
     * <ul>
     *   <li>ALLOW：允许执行（仍需通过其它 gate，如审批）。</li>
     *   <li>DENY：拒绝，deny-first 优先于 ALLOW。</li>
     *   <li>REQUIRE_APPROVAL：不直接放行，转入 {@link com.miniclaude.domain.durable.ApprovalRequest} 路径。</li>
     * </ul>
     */
    public enum Effect { ALLOW, DENY, REQUIRE_APPROVAL }

    /** 规则记录 ID。 */
    private final String id;
    /** 租户 ID。 */
    private final String tenantId;
    /** 规则业务键。 */
    private final String key;
    /** 规则版本（与 VersionedAsset 对齐）。 */
    private final String version;
    /** 作用域（如 tenant、scenario、tool）。 */
    private final String scope;
    /** 动作匹配模式（glob/前缀，由引擎解释）。 */
    private final String actionPattern;
    /** 资源匹配模式。 */
    private final String resourcePattern;
    /** 优先级，数值越大越先评估（引擎约定）。 */
    private final int priority;
    /** 命中时的效果。 */
    private final Effect effect;

    public PolicyRule(String id, String tenantId, String key, String version, String scope,
                      String actionPattern, String resourcePattern, int priority, Effect effect) {
        this.id = id; this.tenantId = tenantId; this.key = key; this.version = version;
        this.scope = scope; this.actionPattern = actionPattern; this.resourcePattern = resourcePattern;
        this.priority = priority; this.effect = effect;
    }

    /** @return 规则 ID */
    public String getId() { return id; }
    /** @return 租户 ID */
    public String getTenantId() { return tenantId; }
    /** @return 规则键 */
    public String getKey() { return key; }
    /** @return 版本 */
    public String getVersion() { return version; }
    /** @return 作用域 */
    public String getScope() { return scope; }
    /** @return 动作模式 */
    public String getActionPattern() { return actionPattern; }
    /** @return 资源模式 */
    public String getResourcePattern() { return resourcePattern; }
    /** @return 优先级 */
    public int getPriority() { return priority; }
    /** @return 效果 */
    public Effect getEffect() { return effect; }
}
