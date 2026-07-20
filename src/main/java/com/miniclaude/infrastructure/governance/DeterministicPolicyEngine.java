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
 * 本地、确定性、<b>deny-first</b> 策略引擎（{@link PolicyEngine} JDBC 实现）。
 *
 * <p><b>为何不用 OPA/外部 PDP</b>（{@code docs/architecture.md}）：
 * 默认拓扑不依赖外部策略服务；本引擎在 JVM 内完成 tenant 规则匹配，避免网络超时
 * 导致「超时即允许」的灾难性语义。</p>
 *
 * <p><b>决策优先级（刻意严格）</b>：
 * <ol>
 *   <li>无匹配规则 → DENY（fail-closed，防止配置遗漏扩大权限）</li>
 *   <li>任意 DENY 规则命中 → DENY（deny-first，低优先级 ALLOW 不能覆盖高优先级 DENY）</li>
 *   <li>最高 priority 层 effect 冲突 → DENY（暴露策略配置错误）</li>
 *   <li>存在 REQUIRE_APPROVAL → 要求人工审批</li>
 *   <li>否则 ALLOW</li>
 * </ol></p>
 *
 * <p>每次 {@link #evaluate} 写审计日志并递增 Micrometer 计数器 {@code agentops.policy.decisions}。</p>
 */
@Service
public class DeterministicPolicyEngine implements PolicyEngine {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final MeterRegistry meters;

    public DeterministicPolicyEngine(JdbcTemplate jdbc, AuditService audit, MeterRegistry meters) {
        this.jdbc = jdbc; this.audit = audit; this.meters = meters;
    }

    /**
     * 管理 API：向 {@code policy_rule} 表插入一条启用规则。
     *
     * <p>不做重复 key 检测——由 DB 唯一约束或上层管理流程保证。</p>
     */
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

    /**
     * 纯函数决策核心：给定已过滤、已排序的规则列表产出 {@link PolicyDecision}。
     *
     * <p>供单元测试直接调用，避免依赖 JDBC。</p>
     *
     * @param rules 非 null；通常已按 priority 降序
     */
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

    /**
     * 简单 glob 风格匹配：{@code *} 匹配任意子串；其余字符按字面量（经 quote 转义）。
     *
     * @param pattern 规则中的 action/resource 模式
     * @param value   请求中的实际 action/resource
     */
    private static boolean match(String pattern, String value) {
        if ("*".equals(pattern)) return true;
        String regex = java.util.regex.Pattern.quote(pattern).replace("*", "\\E.*\\Q");
        return value.matches(regex);
    }
}
