package com.miniclaude.infrastructure.scenario;

import com.miniclaude.domain.scenario.RegulatedScenarioPorts;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * 明确命名的确定性 fake：不连接交易所、OMS、风控、客户、图谱或案例生产系统。
 */
@Component
public final class DeterministicFakeRegulatedAdapters implements
        RegulatedScenarioPorts.InvestigationEvidence,
        RegulatedScenarioPorts.InvestigationVerifier,
        RegulatedScenarioPorts.TradingResearch {

    @Override
    public RegulatedScenarioPorts.Score ruleScore(String subjectRef) {
        return new RegulatedScenarioPorts.Score("DETERMINISTIC_FAKE_RULE_ENGINE",
                new BigDecimal("0.62"), "fake-rules@1.0.0");
    }

    @Override
    public RegulatedScenarioPorts.Score modelScore(String subjectRef) {
        return new RegulatedScenarioPorts.Score("DETERMINISTIC_FAKE_MODEL",
                new BigDecimal("0.71"), "fake-model@1.0.0");
    }

    @Override
    public List<RegulatedScenarioPorts.Evidence> graphAndCaseEvidence(String subjectRef) {
        return Arrays.asList(
                new RegulatedScenarioPorts.Evidence("fake-graph-001",
                        "DETERMINISTIC_FAKE_GRAPH", "1.0.0", "sha256:fake-graph-evidence"),
                new RegulatedScenarioPorts.Evidence("fake-case-001",
                        "DETERMINISTIC_FAKE_CASE_LIBRARY", "1.0.0", "sha256:fake-case-evidence"));
    }

    @Override
    public RegulatedScenarioPorts.Verification verify(
            String recommendation, List<RegulatedScenarioPorts.Evidence> evidence) {
        boolean passed = ("REVIEW".equals(recommendation) || "ESCALATE".equals(recommendation))
                && evidence != null && !evidence.isEmpty();
        return new RegulatedScenarioPorts.Verification(passed,
                "INDEPENDENT_DETERMINISTIC_FAKE_VERIFIER@1.0.0",
                passed ? "recommendation and provenance verified" : "unsafe recommendation");
    }

    @Override
    public RegulatedScenarioPorts.MarketSnapshot market(String instrument) {
        return new RegulatedScenarioPorts.MarketSnapshot(instrument, new BigDecimal("100.00"),
                Instant.parse("2030-01-02T14:30:00Z"), "DETERMINISTIC_FAKE_MARKET_DATA");
    }

    @Override
    public String research(String instrument) {
        return "DETERMINISTIC_FAKE_RESEARCH://" + instrument + "@1.0.0";
    }

    @Override
    public RegulatedScenarioPorts.PositionSnapshot position(String portfolioRef, String instrument) {
        return new RegulatedScenarioPorts.PositionSnapshot(portfolioRef, instrument,
                new BigDecimal("10"), "DETERMINISTIC_FAKE_POSITION_LEDGER");
    }
}
