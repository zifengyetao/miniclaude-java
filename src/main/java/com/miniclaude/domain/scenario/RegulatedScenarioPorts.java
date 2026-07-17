package com.miniclaude.domain.scenario;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * 受监管场景的只读仿真端口。
 *
 * <p>风控端口只能读取评分、图谱和案例证据并验证建议，不能拒绝客户、封禁或冻结账户；
 * 交易端口只能读取行情、研究和持仓，刻意不定义 submit/placeOrder。能力在接口层缺席，
 * 比运行时用布尔开关禁用更难被误调用，也明确了 fake 与生产系统的隔离边界。</p>
 */
public final class RegulatedScenarioPorts {
    private RegulatedScenarioPorts() {}

    public interface InvestigationEvidence {
        /** 规则分、模型分和证据均携带来源或版本，供案例包审计。 */
        Score ruleScore(String subjectRef);
        Score modelScore(String subjectRef);
        List<Evidence> graphAndCaseEvidence(String subjectRef);
    }

    public interface InvestigationVerifier {
        /** 独立检查建议及证据完整性，不负责作出客户不利决定。 */
        Verification verify(String recommendation, List<Evidence> evidence);
    }

    public interface TradingResearch {
        /** 三项能力全部只读；本端口不存在任何订单提交或凭证能力。 */
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
