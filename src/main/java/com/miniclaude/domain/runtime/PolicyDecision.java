package com.miniclaude.domain.runtime;

/**
 * 策略判定的不可变结果。
 *
 * <p>结果明确区分允许、拒绝和需审批；调用方只能把 {@link Outcome#ALLOW} 视为可执行，
 * 对未知值或处理异常应失败关闭。原因用于审计和解释，不应包含密钥等敏感数据。
 */
public final class PolicyDecision {
    public enum Outcome { ALLOW, DENY, REQUIRE_APPROVAL }

    private final Outcome outcome;
    private final String reason;

    private PolicyDecision(Outcome outcome, String reason) {
        this.outcome = outcome;
        this.reason = reason != null ? reason : "";
    }

    public static PolicyDecision allow(String reason) {
        return new PolicyDecision(Outcome.ALLOW, reason);
    }

    public static PolicyDecision deny(String reason) {
        return new PolicyDecision(Outcome.DENY, reason);
    }

    public static PolicyDecision requireApproval(String reason) {
        return new PolicyDecision(Outcome.REQUIRE_APPROVAL, reason);
    }

    public boolean isAllowed() {
        return outcome == Outcome.ALLOW;
    }

    public boolean isApprovalRequired() {
        return outcome == Outcome.REQUIRE_APPROVAL;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public String getReason() {
        return reason;
    }
}
