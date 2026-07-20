package com.miniclaude.domain.runtime;

/**
 * 策略判定的不可变结果值对象。
 * <p>
 * <b>为何放在 domain：</b>Harness 控制面的统一决策语义。
 * <p>
 * <b>不变量：</b>
 * <ul>
 *   <li>仅 {@link Outcome#ALLOW} 时 {@link #isAllowed()} 为 true。</li>
 *   <li>reason 永非 null（空串允许）；不得含密钥。</li>
 * </ul>
 * <p>
 * <b>边界：</b>application 根据 outcome 分支执行/审批/拒绝。
 */
public final class PolicyDecision {

    /**
     * 策略结果枚举。
     * <ul>
     *   <li>ALLOW：可自动继续（仍可能有其它 gate）。</li>
     *   <li>DENY：拒绝，fail-closed。</li>
     *   <li>REQUIRE_APPROVAL：转入 {@link com.miniclaude.domain.durable.DurableOrchestrator#awaitApproval}。</li>
     * </ul>
     */
    public enum Outcome { ALLOW, DENY, REQUIRE_APPROVAL }

    /** 判定结果。 */
    private final Outcome outcome;
    /** 人类可读原因（审计）。 */
    private final String reason;

    private PolicyDecision(Outcome outcome, String reason) {
        this.outcome = outcome;
        this.reason = reason != null ? reason : "";
    }

    /** @param reason 允许原因 */
    public static PolicyDecision allow(String reason) {
        return new PolicyDecision(Outcome.ALLOW, reason);
    }

    /** @param reason 拒绝原因 */
    public static PolicyDecision deny(String reason) {
        return new PolicyDecision(Outcome.DENY, reason);
    }

    /** @param reason 需审批原因 */
    public static PolicyDecision requireApproval(String reason) {
        return new PolicyDecision(Outcome.REQUIRE_APPROVAL, reason);
    }

    /** @return 是否允许自动执行 */
    public boolean isAllowed() {
        return outcome == Outcome.ALLOW;
    }

    /** @return 是否需人工审批 */
    public boolean isApprovalRequired() {
        return outcome == Outcome.REQUIRE_APPROVAL;
    }

    /** @return 结果枚举 */
    public Outcome getOutcome() {
        return outcome;
    }

    /** @return 原因说明 */
    public String getReason() {
        return reason;
    }
}
