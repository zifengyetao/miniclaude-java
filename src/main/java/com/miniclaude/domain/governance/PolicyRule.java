package com.miniclaude.domain.governance;

/**
 * 确定性策略引擎使用的版本化规则值对象。
 *
 * <p>priority 只决定稳定排序与同优先级冲突检测，不会让高优先级 ALLOW 覆盖任意 DENY。
 * 这是 deny-first 的关键：命中拒绝即否决；无匹配或效果冲突同样失败关闭。</p>
 */
public final class PolicyRule {
    /** REQUIRE_APPROVAL 不代表放行，而是要求执行链转入人工批准路径。 */
    public enum Effect { ALLOW, DENY, REQUIRE_APPROVAL }
    private final String id;
    private final String tenantId;
    private final String key;
    private final String version;
    private final String scope;
    private final String actionPattern;
    private final String resourcePattern;
    private final int priority;
    private final Effect effect;

    public PolicyRule(String id, String tenantId, String key, String version, String scope,
                      String actionPattern, String resourcePattern, int priority, Effect effect) {
        this.id = id; this.tenantId = tenantId; this.key = key; this.version = version;
        this.scope = scope; this.actionPattern = actionPattern; this.resourcePattern = resourcePattern;
        this.priority = priority; this.effect = effect;
    }

    public String getId() { return id; }
    public String getTenantId() { return tenantId; }
    public String getKey() { return key; }
    public String getVersion() { return version; }
    public String getScope() { return scope; }
    public String getActionPattern() { return actionPattern; }
    public String getResourcePattern() { return resourcePattern; }
    public int getPriority() { return priority; }
    public Effect getEffect() { return effect; }
}
