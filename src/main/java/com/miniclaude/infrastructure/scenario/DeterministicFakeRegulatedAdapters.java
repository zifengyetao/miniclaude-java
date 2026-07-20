package com.miniclaude.infrastructure.scenario;

import com.miniclaude.domain.scenario.RegulatedScenarioPorts;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * 受监管仿真域的<b>确定性 Fake 适配器</b>（风控调查 + 交易辅助场景）。
 *
 * <p><b>为何必须 Fake</b>（{@code docs/overview.md} 硬约束）：
 * <ul>
 *   <li>禁止连接真实交易所、OMS、生产风控引擎、客户主数据或知识图谱</li>
 *   <li>禁止实现 {@code submitOrder}、{@code freezeAccount}、{@code rejectCustomer} 等
 *       不利客户处置接口——<b>接口不存在即能力不存在</b>，编排层误调也无法越权</li>
 *   <li>固定返回值 + 显式 {@code DETERMINISTIC_FAKE_*} 来源标记，测试可复现且不会
 *       与生产证据混淆</li>
 * </ul></p>
 *
 * <p><b>实现的多端口</b>：单 Bean 同时实现 {@link RegulatedScenarioPorts} 下
 * 调查证据、独立验证、交易研究三个端口，减少 Spring 装配复杂度。</p>
 *
 * <p><b>生产替换路径</b>：须以独立 Bean + 网络隔离 + 四眼审批替换；替换前须重新
 * 评审 {@code docs/security.md} 与 {@code docs/agent-flows.md} 监管链路。</p>
 */
@Component
public final class DeterministicFakeRegulatedAdapters implements
        RegulatedScenarioPorts.InvestigationEvidence,
        RegulatedScenarioPorts.InvestigationVerifier,
        RegulatedScenarioPorts.TradingResearch {

    /** 规则引擎评分：固定 0.62，来源 {@code fake-rules@1.0.0}，非实时风控结果 */
    @Override
    public RegulatedScenarioPorts.Score ruleScore(String subjectRef) {
        return new RegulatedScenarioPorts.Score("DETERMINISTIC_FAKE_RULE_ENGINE",
                new BigDecimal("0.62"), "fake-rules@1.0.0");
    }

    /** 模型评分：固定 0.71，与 ruleScore 分离以测试「多信号融合」编排逻辑 */
    @Override
    public RegulatedScenarioPorts.Score modelScore(String subjectRef) {
        return new RegulatedScenarioPorts.Score("DETERMINISTIC_FAKE_MODEL",
                new BigDecimal("0.71"), "fake-model@1.0.0");
    }

    /**
     * 返回图谱与案例库两条固定证据引用。
     *
     * <p>{@code subjectRef} 当前未参与构造——Fake 阶段忽略主体差异；生产适配器须按主体拉取。</p>
     */
    @Override
    public List<RegulatedScenarioPorts.Evidence> graphAndCaseEvidence(String subjectRef) {
        return Arrays.asList(
                new RegulatedScenarioPorts.Evidence("fake-graph-001",
                        "DETERMINISTIC_FAKE_GRAPH", "1.0.0", "sha256:fake-graph-evidence"),
                new RegulatedScenarioPorts.Evidence("fake-case-001",
                        "DETERMINISTIC_FAKE_CASE_LIBRARY", "1.0.0", "sha256:fake-case-evidence"));
    }

    /**
     * 独立验证器：只接受 {@code REVIEW}/{@code ESCALE} 建议且证据非空。
     *
     * <p>刻意<b>不</b>模拟 {@code REJECT}/{@code FREEZE} 等不利客户动作——监管场景
     * 输出上限为「建议复核/升级」，验证器白名单与此对齐。</p>
     */
    @Override
    public RegulatedScenarioPorts.Verification verify(
            String recommendation, List<RegulatedScenarioPorts.Evidence> evidence) {
        // fake 验证器只验证建议白名单和证据存在性，用于测试独立验证边界而非生产裁决。
        boolean passed = ("REVIEW".equals(recommendation) || "ESCALATE".equals(recommendation))
                && evidence != null && !evidence.isEmpty();
        return new RegulatedScenarioPorts.Verification(passed,
                "INDEPENDENT_DETERMINISTIC_FAKE_VERIFIER@1.0.0",
                passed ? "recommendation and provenance verified" : "unsafe recommendation");
    }

    /**
     * 固定行情快照：2030 年时间戳 + 100.00 价格。
     *
     * <p>禁止被 UI 或下游当作实时行情；来源字段 {@code DETERMINISTIC_FAKE_MARKET_DATA} 供审计识别。</p>
     */
    @Override
    public RegulatedScenarioPorts.MarketSnapshot market(String instrument) {
        // 固定时间和价格保证测试可复现；不得将其解释为实时市场数据。
        return new RegulatedScenarioPorts.MarketSnapshot(instrument, new BigDecimal("100.00"),
                Instant.parse("2030-01-02T14:30:00Z"), "DETERMINISTIC_FAKE_MARKET_DATA");
    }

    /** 返回带 instrument 占位符的固定研究摘要 URI，不访问外部研报 API */
    @Override
    public String research(String instrument) {
        return "DETERMINISTIC_FAKE_RESEARCH://" + instrument + "@1.0.0";
    }

    /** 固定持仓 10 股；ledger 来源标记为 Fake，非真实账本 */
    @Override
    public RegulatedScenarioPorts.PositionSnapshot position(String portfolioRef, String instrument) {
        return new RegulatedScenarioPorts.PositionSnapshot(portfolioRef, instrument,
                new BigDecimal("10"), "DETERMINISTIC_FAKE_POSITION_LEDGER");
    }
}
