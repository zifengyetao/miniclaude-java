package com.miniclaude.domain.scenario;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 受监管场景（风控调查 / 交易辅助）的只读仿真 Outbound Port 命名空间。
 * <p>
 * <b>为何放在 domain：</b>通过<b>接口能力缺席</b>表达硬约束——无 submitOrder、无 freezeAccount，
 * 比运行时 boolean 开关更难误调用。
 * <p>
 * <b>不变量：</b>所有端口只读或验证；客户不利处置必须在 application+审批链，不得在此端口实现。
 * <p>
 * <b>边界：</b>infrastructure Fake 行情/持仓/证据；application {@code RegulatedScenarioService} + {@link RegulatedSimulationPolicy}。
 */
public final class RegulatedScenarioPorts {
    private RegulatedScenarioPorts() {}

    /**
     * 风控调查：读取评分与证据（无账户处置能力）。
     */
    public interface InvestigationEvidence {
        /** 规则引擎分，含 source/version 供审计。 */
        Score ruleScore(String subjectRef);
        /** 模型分。 */
        Score modelScore(String subjectRef);
        /** 关联图谱与案例证据列表。 */
        List<Evidence> graphAndCaseEvidence(String subjectRef);
    }

    /**
     * 调查结论独立验证（不作出封禁/拒绝客户决定）。
     */
    public interface InvestigationVerifier {
        Verification verify(String recommendation, List<Evidence> evidence);
    }

    /**
     * 交易研究：行情/研报/持仓只读（<b>故意无</b> placeOrder/submit）。
     */
    public interface TradingResearch {
        MarketSnapshot market(String instrument);
        String research(String instrument);
        PositionSnapshot position(String portfolioRef, String instrument);
    }

    /** 评分值对象。 */
    public static final class Score {
        /** 来源系统/规则名。 */
        public final String source;
        /** 分数值。 */
        public final BigDecimal value;
        /** 规则/模型版本。 */
        public final String version;
        public Score(String source, BigDecimal value, String version) {
            this.source = source; this.value = value; this.version = version;
        }
    }

    /** 证据条目（可哈希 digest 防篡改）。 */
    public static final class Evidence {
        public final String evidenceId;
        public final String source;
        public final String sourceVersion;
        public final String digest;
        public Evidence(String evidenceId, String source, String sourceVersion, String digest) {
            this.evidenceId = evidenceId; this.source = source;
            this.sourceVersion = sourceVersion; this.digest = digest;
        }
    }

    /** 验证结果。 */
    public static final class Verification {
        public final boolean passed;
        public final String verifier;
        public final String reason;
        public Verification(boolean passed, String verifier, String reason) {
            this.passed = passed; this.verifier = verifier; this.reason = reason;
        }
    }

    /** 行情快照（含时效 asOf）。 */
    public static final class MarketSnapshot {
        public final String instrument;
        public final BigDecimal price;
        public final Instant asOf;
        public final String source;
        public MarketSnapshot(String instrument, BigDecimal price, Instant asOf, String source) {
            this.instrument = instrument; this.price = price; this.asOf = asOf; this.source = source;
        }
    }

    /** 持仓快照（只读）。 */
    public static final class PositionSnapshot {
        public final String portfolioRef;
        public final String instrument;
        public final BigDecimal quantity;
        public final String source;
        public PositionSnapshot(String portfolioRef, String instrument, BigDecimal quantity, String source) {
            this.portfolioRef = portfolioRef; this.instrument = instrument;
            this.quantity = quantity; this.source = source;
        }
    }
}
