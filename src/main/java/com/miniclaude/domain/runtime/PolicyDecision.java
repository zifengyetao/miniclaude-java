package com.miniclaude.domain.runtime;

/**
 * 策略判定结果；默认由调用方按拒绝处理未知状态。
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
