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
 * 独立的受监管仿真安全域策略，状态按租户隔离。
 *
 * <p>该域统一执行租户级 kill switch、截止时间、标的白名单和确定性交易限额。
 * kill switch 在启动、执行和恢复等边界重复检查，使已经等待审批的运行也不能绕过停机。
 * 场景的进化能力由种子配置限制为 L1：仅允许低风险提示/配置级演进，不允许自主改变
 * 受监管策略、审批结构或外部动作能力。</p>
 */
@Component
public final class RegulatedSimulationPolicy {
    public static final String DOMAIN = "REGULATED_SIMULATION";
    public static final int MAX_MARKET_AGE_SECONDS = 60;
    public static final BigDecimal MAX_NOTIONAL = new BigDecimal("100000");
    public static final BigDecimal MAX_POSITION = new BigDecimal("1000");
    public static final BigDecimal MAX_STRESS_LOSS = new BigDecimal("25000");
    private static final Set<String> ALLOWLIST = Collections.unmodifiableSet(
            new HashSet<>(Arrays.asList("AAPL", "MSFT", "SPY")));

    private final Map<String, Boolean> killed = new ConcurrentHashMap<>();
    private final Map<String, String> idempotentRuns = new ConcurrentHashMap<>();

    public void guard(String tenant, Instant deadline) {
        requireTenant(tenant);
        // why：紧急停机优先于业务进度；开启后新运行和恢复中的运行都必须立即阻断。
        if (Boolean.TRUE.equals(killed.get(tenant))) {
            throw new SecurityException("regulated tenant kill switch is active");
        }
        // why：受监管审批不能无限期复用，过期上下文中的行情和判断必须重新生成。
        if (deadline == null || !Instant.now().isBefore(deadline)) {
            throw new IllegalStateException("regulated run deadline expired");
        }
    }

    public void validatePreTrade(String instrument, BigDecimal orderQuantity,
                                 BigDecimal currentQuantity, BigDecimal price,
                                 BigDecimal stressLossPct, boolean marketOpen,
                                 long marketAgeSeconds) {
        // 下列检查全部是确定性硬阻断；LLM 提案或人工审批都不能覆盖这些上限。
        if (!ALLOWLIST.contains(instrument)) throw new SecurityException("instrument not allowlisted");
        BigDecimal notional = orderQuantity.abs().multiply(price);
        BigDecimal resultingPosition = currentQuantity.add(orderQuantity).abs();
        BigDecimal stressLoss = notional.multiply(stressLossPct.abs());
        if (notional.compareTo(MAX_NOTIONAL) > 0) throw new SecurityException("notional limit exceeded");
        if (resultingPosition.compareTo(MAX_POSITION) > 0) throw new SecurityException("position limit exceeded");
        if (stressLoss.compareTo(MAX_STRESS_LOSS) > 0) throw new SecurityException("loss limit exceeded");
        if (!marketOpen) throw new SecurityException("market is closed");
        if (marketAgeSeconds < 0 || marketAgeSeconds > MAX_MARKET_AGE_SECONDS) {
            // why：未来时间或超过时效的快照都不能作为交易草稿的可信输入。
            throw new SecurityException("market data is stale");
        }
    }

    public boolean isKilled(String tenant) {
        requireTenant(tenant);
        return Boolean.TRUE.equals(killed.get(tenant));
    }

    public boolean setKilled(String tenant, boolean active) {
        requireTenant(tenant);
        killed.put(tenant, active);
        return active;
    }

    public String findRun(String tenant, String key) {
        return key == null ? null : idempotentRuns.get(tenant + ":" + key);
    }

    public void rememberRun(String tenant, String key, String runId) {
        if (key != null && !key.trim().isEmpty()) {
            // 相同租户和幂等键只记住首个运行，避免重试重复创建审批链。
            idempotentRuns.putIfAbsent(tenant + ":" + key.trim(), runId);
        }
    }

    public Set<String> instrumentAllowlist() { return ALLOWLIST; }

    private static void requireTenant(String tenant) {
        // why：受监管状态必须落到明确租户，禁止共享 default 域造成审批或停机串租。
        if (tenant == null || tenant.trim().isEmpty() || "default".equals(tenant)) {
            throw new IllegalArgumentException("explicit regulated tenant is required");
        }
    }
}
