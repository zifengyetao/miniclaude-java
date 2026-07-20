package com.miniclaude.domain.scenario;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 受监管仿真域的独立安全策略（租户级 kill-switch + 交易限额 + 幂等 Run 记忆）。
 * <p>
 * <b>为何放在 domain：</b>监管硬约束（标的白名单、名义/持仓/ stress 上限、行情时效）是领域规则，
 * LLM 与人工审批均不能覆盖；放在 domain 便于 Scenario 与 Durable 边界统一调用。
 * <p>
 * <b>不变量：</b>
 * <ul>
 *   <li>tenant 必须显式且非 {@code "default"}，防止串租。</li>
 *   <li>kill switch 激活时 {@link #guard} 立即 SecurityException（含 resume 路径）。</li>
 *   <li>进化能力在受监管域种子配置上限 L1（见类文档与 application Catalog）。</li>
 * </ul>
 * <p>
 * <b>边界：</b>application RegulatedScenarioService 在启动/步进/恢复前调用；
 * infrastructure 为内存 Map（非持久化 kill 状态，重启清空——生产应外置）。
 * <p>
 * <b>状态（按租户）：</b>
 * <ul>
 *   <li>{@code killed[tenant]}：false/ absent → 正常；true → 全部 guard 失败。</li>
 *   <li>{@code idempotentRuns[tenant:key]}：首次 Run ID，重试不得重复创建审批链。</li>
 * </ul>
 */
@Component
public final class RegulatedSimulationPolicy {

    /** 安全域标识，用于审计与策略 scope。 */
    public static final String DOMAIN = "REGULATED_SIMULATION";
    /** 行情快照最大允许年龄（秒）；超龄视为 stale。 */
    public static final int MAX_MARKET_AGE_SECONDS = 60;
    /** 单笔名义金额上限（美元）。 */
    public static final BigDecimal MAX_NOTIONAL = new BigDecimal("100000");
    /** 结果持仓绝对值上限。 */
    public static final BigDecimal MAX_POSITION = new BigDecimal("1000");
    /** stress 损失上限（notional × stressLossPct）。 */
    public static final BigDecimal MAX_STRESS_LOSS = new BigDecimal("25000");
    /** 允许交易的标的白名单（仿真）。 */
    private static final Set<String> ALLOWLIST = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("AAPL", "MSFT", "SPY")));

    /** 租户 → kill switch 是否激活。 */
    private final Map<String, Boolean> killed = new ConcurrentHashMap<>();
    /** 租户:幂等键 → 首个 Run ID。 */
    private final Map<String, String> idempotentRuns = new ConcurrentHashMap<>();

    /**
     * Run 级门禁：kill switch + 审批截止时间。
     * <p>
     * <b>转移条件：</b>
     * <ul>
     *   <li>killed[tenant]=true → SecurityException（立即阻断，含 WAITING_APPROVAL 恢复）。</li>
     *   <li>deadline null 或 now ≥ deadline → IllegalStateException（审批上下文过期）。</li>
     * </ul>
     *
     * @param tenant   显式受监管租户
     * @param deadline Run 审批有效截止时间
     */
    public void guard(String tenant, Instant deadline) {
        requireTenant(tenant);
        if (Boolean.TRUE.equals(killed.get(tenant))) {
            throw new SecurityException("regulated tenant kill switch is active");
        }
        if (deadline == null || !Instant.now().isBefore(deadline)) {
            throw new IllegalStateException("regulated run deadline expired");
        }
    }

    /**
     * 交易草稿前置校验（确定性硬阻断，不可被 LLM/审批覆盖）。
     * <p>
     * <b>逐项条件（任一失败 → SecurityException）：</b>
     * instrument ∈ ALLOWLIST；notional ≤ MAX_NOTIONAL；|current+order| ≤ MAX_POSITION；
     * stressLoss ≤ MAX_STRESS_LOSS；marketOpen；0 ≤ marketAgeSeconds ≤ MAX_MARKET_AGE_SECONDS。
     */
    public void validatePreTrade(String instrument, BigDecimal orderQuantity,
                                 BigDecimal currentQuantity, BigDecimal price,
                                 BigDecimal stressLossPct, boolean marketOpen,
                                 long marketAgeSeconds) {
        if (!ALLOWLIST.contains(instrument)) throw new SecurityException("instrument not allowlisted");
        BigDecimal notional = orderQuantity.abs().multiply(price);
        BigDecimal resultingPosition = currentQuantity.add(orderQuantity).abs();
        BigDecimal stressLoss = notional.multiply(stressLossPct.abs());
        if (notional.compareTo(MAX_NOTIONAL) > 0) throw new SecurityException("notional limit exceeded");
        if (resultingPosition.compareTo(MAX_POSITION) > 0) throw new SecurityException("position limit exceeded");
        if (stressLoss.compareTo(MAX_STRESS_LOSS) > 0) throw new SecurityException("loss limit exceeded");
        if (!marketOpen) throw new SecurityException("market is closed");
        if (marketAgeSeconds < 0 || marketAgeSeconds > MAX_MARKET_AGE_SECONDS) {
            throw new SecurityException("market data is stale");
        }
    }

    /**
     * 查询租户 kill switch 是否激活。
     *
     * @return true 当 killed[tenant]=true
     */
    public boolean isKilled(String tenant) {
        requireTenant(tenant);
        return Boolean.TRUE.equals(killed.get(tenant));
    }

    /**
     * 设置租户 kill switch。
     * <p>
     * <b>状态转移：</b>active=true → 该租户后续 guard 全部失败；active=false → 恢复（新 Run/resume 仍须满足 deadline 等）。
     *
     * @return 设置后的 active 值
     */
    public boolean setKilled(String tenant, boolean active) {
        requireTenant(tenant);
        killed.put(tenant, active);
        return active;
    }

    /**
     * 按幂等键查找已创建的 Run ID。
     *
     * @return 首个 runId，或 null
     */
    public String findRun(String tenant, String key) {
        return key == null ? null : idempotentRuns.get(tenant + ":" + key);
    }

    /**
     * 记住 tenant+key 对应的首个 Run（putIfAbsent，重试不覆盖）。
     */
    public void rememberRun(String tenant, String key, String runId) {
        if (key != null && !key.trim().isEmpty()) {
            idempotentRuns.putIfAbsent(tenant + ":" + key.trim(), runId);
        }
    }

    /** @return 不可变标的白名单视图 */
    public Set<String> instrumentAllowlist() { return ALLOWLIST; }

    /** 受监管租户必须显式，禁止 default 共享域。 */
    private static void requireTenant(String tenant) {
        if (tenant == null || tenant.trim().isEmpty() || "default".equals(tenant)) {
            throw new IllegalArgumentException("explicit regulated tenant is required");
        }
    }
}
