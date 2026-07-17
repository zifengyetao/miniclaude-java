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

/** 独立的受监管仿真安全域策略；状态按租户隔离。 */
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
        if (Boolean.TRUE.equals(killed.get(tenant))) {
            throw new SecurityException("regulated tenant kill switch is active");
        }
        if (deadline == null || !Instant.now().isBefore(deadline)) {
            throw new IllegalStateException("regulated run deadline expired");
        }
    }

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
            idempotentRuns.putIfAbsent(tenant + ":" + key.trim(), runId);
        }
    }

    public Set<String> instrumentAllowlist() { return ALLOWLIST; }

    private static void requireTenant(String tenant) {
        if (tenant == null || tenant.trim().isEmpty() || "default".equals(tenant)) {
            throw new IllegalArgumentException("explicit regulated tenant is required");
        }
    }
}
