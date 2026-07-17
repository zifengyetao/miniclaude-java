package com.miniclaude.domain.scenario;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 受监管场景的只读仿真端口。刻意不定义拒绝客户、封禁账户或 submit/place order 能力。
 */
public final class RegulatedScenarioPorts {
    private RegulatedScenarioPorts() {}

    public interface InvestigationEvidence {
        Score ruleScore(String subjectRef);
        Score modelScore(String subjectRef);
        List<Evidence> graphAndCaseEvidence(String subjectRef);
    }

    public interface InvestigationVerifier {
        Verification verify(String recommendation, List<Evidence> evidence);
    }

    public interface TradingResearch {
        MarketSnapshot market(String instrument);
        String research(String instrument);
        PositionSnapshot position(String portfolioRef, String instrument);
    }

    public static final class Score {
        public final String source;
        public final BigDecimal value;
        public final String version;
        public Score(String source, BigDecimal value, String version) {
            this.source = source; this.value = value; this.version = version;
        }
    }

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

    public static final class Verification {
        public final boolean passed;
        public final String verifier;
        public final String reason;
        public Verification(boolean passed, String verifier, String reason) {
            this.passed = passed; this.verifier = verifier; this.reason = reason;
        }
    }

    public static final class MarketSnapshot {
        public final String instrument;
        public final BigDecimal price;
        public final Instant asOf;
        public final String source;
        public MarketSnapshot(String instrument, BigDecimal price, Instant asOf, String source) {
            this.instrument = instrument; this.price = price; this.asOf = asOf; this.source = source;
        }
    }

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
