package com.miniclaude.domain.governance;

public final class PolicyRule {
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
