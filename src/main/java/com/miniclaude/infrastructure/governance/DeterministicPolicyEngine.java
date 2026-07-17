package com.miniclaude.infrastructure.governance;

import com.miniclaude.application.governance.AuditService;
import com.miniclaude.domain.governance.PolicyRule;
import com.miniclaude.domain.runtime.PolicyDecision;
import com.miniclaude.domain.runtime.PolicyEngine;
import com.miniclaude.domain.runtime.PolicyRequest;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * 本地、确定性、deny-first 策略引擎；无任何外部网络调用。
 *
 * <p>相同租户规则与请求必得相同决策，外部服务超时不会改变授权语义。安全优先级为：
 * 任意 DENY ＞ 同优先级冲突时 DENY ＞ REQUIRE_APPROVAL ＞ ALLOW；没有匹配规则也 DENY。
 * 因此低优先级拒绝不会被高优先级允许覆盖，这是刻意的全局否决语义。</p>
 */
@Service
public class DeterministicPolicyEngine implements PolicyEngine {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final MeterRegistry meters;

    public DeterministicPolicyEngine(JdbcTemplate jdbc, AuditService audit, MeterRegistry meters) {
        this.jdbc = jdbc; this.audit = audit; this.meters = meters;
    }

    public PolicyRule addRule(String tenant, String key, String version, String scope,
                              String actionPattern, String resourcePattern, int priority,
                              PolicyRule.Effect effect) {
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO policy_rule (id,tenant_id,rule_key,version,scope,action_pattern,"
                        + "resource_pattern,priority,effect,enabled,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                id, tenant, key, version, scope, actionPattern, resourcePattern, priority,
                effect.name(), true, Timestamp.from(Instant.now()));
        return new PolicyRule(id, tenant, key, version, scope, actionPattern, resourcePattern, priority, effect);
    }

    @Override
    public PolicyDecision evaluate(PolicyRequest request) {
        String tenant = request.getContext().getTenantId();
        // 首先按 tenant 过滤，跨租户规则绝不能参与当前主体的授权决策。
        List<PolicyRule> matching = jdbc.query("SELECT * FROM policy_rule WHERE tenant_id=? AND enabled=TRUE",
                (rs, n) -> new PolicyRule(rs.getString("id"), rs.getString("tenant_id"),
                        rs.getString("rule_key"), rs.getString("version"), rs.getString("scope"),
                        rs.getString("action_pattern"), rs.getString("resource_pattern"),
                        rs.getInt("priority"), PolicyRule.Effect.valueOf(rs.getString("effect"))),
                tenant).stream()
                .filter(rule -> match(rule.getScope(), tenant)
                        && match(rule.getActionPattern(), request.getAction())
                        && match(rule.getResourcePattern(), request.getResource()))
                .sorted(Comparator.comparingInt(PolicyRule::getPriority).reversed()
                        .thenComparing(PolicyRule::getKey).thenComparing(PolicyRule::getVersion))
                .collect(Collectors.toList());

        PolicyDecision decision = decide(matching);
        audit.append(tenant, "AGENT", request.getContext().getSessionId(), "POLICY_EVALUATED",
                "POLICY", request.getAction() + ":" + request.getResource(),
                decision.getOutcome().name(), matching.toString(), request.getContext().getTraceId(),
                request.getContext().getRunId());
        meters.counter("agentops.policy.decisions", "outcome", decision.getOutcome().name()).increment();
        return decision;
    }

    public static PolicyDecision decide(List<PolicyRule> rules) {
        // 默认拒绝：配置缺失、规则未部署或模式不匹配都不能意外扩大权限。
        if (rules.isEmpty()) return PolicyDecision.deny("no matching allow rule (fail-closed)");
        // deny-first 跨越 priority；priority 不能成为绕过显式禁止规则的手段。
        if (rules.stream().anyMatch(r -> r.getEffect() == PolicyRule.Effect.DENY)) {
            return PolicyDecision.deny("matching deny rule (deny-first)");
        }
        int highest = rules.get(0).getPriority();
        long effectsAtHighest = rules.stream().filter(r -> r.getPriority() == highest)
                .map(PolicyRule::getEffect).distinct().count();
        // 最高优先级出现歧义时不猜测意图，以拒绝暴露策略配置冲突。
        if (effectsAtHighest > 1) return PolicyDecision.deny("conflicting policy effects (fail-closed)");
        if (rules.stream().anyMatch(r -> r.getEffect() == PolicyRule.Effect.REQUIRE_APPROVAL)) {
            return PolicyDecision.requireApproval("matching approval rule");
        }
        return PolicyDecision.allow("matching allow rule");
    }

    private static boolean match(String pattern, String value) {
        if ("*".equals(pattern)) return true;
        String regex = java.util.regex.Pattern.quote(pattern).replace("*", "\\E.*\\Q");
        return value.matches(regex);
    }
}
