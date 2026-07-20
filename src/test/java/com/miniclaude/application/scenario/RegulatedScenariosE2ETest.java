package com.miniclaude.application.scenario;

import com.miniclaude.domain.durable.ApprovalRequest;
import com.miniclaude.domain.platform.AgentRun;
import com.miniclaude.domain.scenario.RegulatedScenarioPorts;
import com.miniclaude.domain.scenario.ScenarioArtifact;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:regulated-scenarios;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "platform.orchestrator=local"
})
@AutoConfigureMockMvc
/**
 * 受监管仿真场景的端到端安全契约测试。
 *
 * <p>覆盖 PII、风控仅建议、确定性交易限额、四眼职责分离、kill switch 和“无 submit”
 * 能力。所有数据来自 deterministic fake，断言旨在证明系统即使获批也只生成仿真制品。</p>
 */
class RegulatedScenariosE2ETest {
    @Autowired RegulatedScenarioService scenarios;
    @Autowired MockMvc mvc;

    /** REST 层 start/status/artifacts/approval-stage 与受监管场景编排一致。 */
    @Test
    void exposesUnifiedRestStartStatusArtifactsAndApprovalStage() throws Exception {
        String response = mvc.perform(post("/api/v1/scenarios/risk-investigation/start")
                        .header("X-Tenant-Id", "regulated-rest")
                        .header("Idempotency-Key", "rest-start-1")
                        .contentType("application/json")
                        .content("{\"proposer\":\"risk-proposer\",\"subjectRef\":\"fake-subject\","
                                + "\"narrative\":\"test@example.com\",\"requestedAction\":\"REVIEW\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("WAITING_APPROVAL"))
                .andReturn().getResponse().getContentAsString();
        String runId = new com.fasterxml.jackson.databind.ObjectMapper().readTree(response)
                .get("id").asText();
        mvc.perform(get("/api/v1/scenarios/risk-investigation/runs/{id}", runId)
                        .header("X-Tenant-Id", "regulated-rest"))
                .andExpect(status().isOk());
        mvc.perform(get("/api/v1/scenarios/risk-investigation/runs/{id}/artifacts", runId)
                        .header("X-Tenant-Id", "regulated-rest"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'INVESTIGATION_CASE_PACKAGE')]").isNotEmpty());
        mvc.perform(get("/api/v1/scenarios/risk-investigation/runs/{id}/approval-stage", runId)
                        .header("X-Tenant-Id", "regulated-rest"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(2));
    }

    /** 四眼审批 + PII 掩码后应 SUCCEEDED，产物不含明文邮箱/手机号。 */
    @Test
    void investigationProducesMaskedVerifiedSuggestionAfterFourEyes() {
        String tenant = "regulated-investigation";
        Map<String, Object> input = investigation("reviewer@example.com 13800138000");
        AgentRun waiting = scenarios.start(tenant, RegulatedScenarioCatalog.INVESTIGATION,
                input, "investigation-idempotency");
        // 相同幂等键必须复用运行，避免同一调查被重试成两套审批。
        AgentRun replay = scenarios.start(tenant, RegulatedScenarioCatalog.INVESTIGATION,
                input, "investigation-idempotency");
        assertThat(waiting.getStatus()).isEqualTo(AgentRun.Status.WAITING_APPROVAL);
        assertThat(replay.getId()).isEqualTo(waiting.getId());
        String packageContent = artifact(tenant, waiting.getId(), "INVESTIGATION_CASE_PACKAGE").getContent();
        assertThat(packageContent).contains("[PII]").contains("\"automaticAdverseAction\":false")
                .contains("evidenceProvenance").doesNotContain("reviewer@example.com")
                .doesNotContain("13800138000");

        approveBoth(tenant, waiting.getId(), "investigator-a", "investigator-b");
        AgentRun completed = scenarios.continueRun(
                tenant, RegulatedScenarioCatalog.INVESTIGATION, waiting.getId());
        assertThat(completed.getStatus()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(artifact(tenant, waiting.getId(), "VERIFIED_RECOMMENDATION").getContent())
                .contains("\"automaticAdverseAction\":false")
                .containsAnyOf("\"recommendation\":\"REVIEW\"", "\"recommendation\":\"ESCALATE\"");
    }

    /** requestedAction=BAN 属于自动不利决定，应 FAILED 并产出 SAFETY_BLOCK。 */
    @Test
    void blocksAutomaticAdverseDecision() {
        // why：BAN 属于自动客户不利决定，超出 REVIEW/ESCALATE 建议白名单。
        Map<String, Object> input = investigation("masked");
        input.put("requestedAction", "BAN");
        AgentRun run = scenarios.start("regulated-auto-block",
                RegulatedScenarioCatalog.INVESTIGATION, input, null);
        assertThat(run.getStatus()).isEqualTo(AgentRun.Status.FAILED);
        assertThat(artifact("regulated-auto-block", run.getId(), "SAFETY_BLOCK").getContent())
                .contains("automatic reject/deny/ban/freeze decision is forbidden");
    }

    /** 超 notional 限额与 stale market data 在审批前即 FAILED。 */
    @Test
    void deterministicRiskEngineBlocksLimitsAndStaleMarket() {
        // 硬限额和行情时效先于人工审批，不能靠审批覆盖确定性安全策略。
        Map<String, Object> limit = trade();
        limit.put("quantity", 2000);
        AgentRun limited = scenarios.start("regulated-limit",
                RegulatedScenarioCatalog.TRADING, limit, null);
        assertThat(limited.getStatus()).isEqualTo(AgentRun.Status.FAILED);
        assertThat(artifact("regulated-limit", limited.getId(), "SAFETY_BLOCK").getContent())
                .contains("notional limit exceeded");

        Map<String, Object> stale = trade();
        stale.put("marketDataAgeSeconds", 61);
        AgentRun staleRun = scenarios.start("regulated-stale",
                RegulatedScenarioCatalog.TRADING, stale, null);
        assertThat(staleRun.getStatus()).isEqualTo(AgentRun.Status.FAILED);
        assertThat(artifact("regulated-stale", staleRun.getId(), "SAFETY_BLOCK").getContent())
                .contains("market data is stale");
    }

    /** 仅一名审批人批准时 continueRun 应 SecurityException（需两名非提案人）。 */
    @Test
    void oneApprovalIsInsufficient() {
        // 四眼不是“存在审批即可”，恢复点必须看到两个不同非提案人。
        String tenant = "regulated-single-approval";
        AgentRun run = scenarios.start(tenant, RegulatedScenarioCatalog.TRADING, trade(), null);
        ApprovalRequest first = scenarios.approvalStage(tenant, run.getId()).get(0);
        scenarios.decide(tenant, run.getId(), first.getId(), "approver-a", "APPROVED", "ok");
        assertThatThrownBy(() -> scenarios.continueRun(
                tenant, RegulatedScenarioCatalog.TRADING, run.getId()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("two distinct non-proposer approvals");
    }

    /** 提案人不得自批；同一 approver 不得批准两次。 */
    @Test
    void samePersonCannotApproveTwiceAndProposerCannotApprove() {
        // 同人重复批准和提案人自批分别验证人数与职责分离两项独立约束。
        String tenant = "regulated-separation";
        AgentRun run = scenarios.start(tenant, RegulatedScenarioCatalog.TRADING, trade(), null);
        List<ApprovalRequest> requests = scenarios.approvalStage(tenant, run.getId());
        assertThatThrownBy(() -> scenarios.decide(tenant, run.getId(), requests.get(0).getId(),
                "trade-proposer", "APPROVED", "self"))
                .isInstanceOf(SecurityException.class).hasMessageContaining("proposer cannot");
        scenarios.decide(tenant, run.getId(), requests.get(0).getId(),
                "approver-a", "APPROVED", "ok");
        assertThatThrownBy(() -> scenarios.decide(tenant, run.getId(), requests.get(1).getId(),
                "approver-a", "APPROVED", "again"))
                .isInstanceOf(SecurityException.class).hasMessageContaining("different approvers");
    }

    /** 租户 kill switch 开启时禁止 start 新运行。 */
    @Test
    void killSwitchStopsNewRuns() {
        // kill switch 是租户级紧急阻断，开启后连新运行创建都不允许。
        String tenant = "regulated-killed";
        scenarios.setKillSwitch(tenant, true, "security-officer");
        assertThatThrownBy(() -> scenarios.start(
                tenant, RegulatedScenarioCatalog.TRADING, trade(), null))
                .isInstanceOf(SecurityException.class).hasMessageContaining("kill switch");
        scenarios.setKillSwitch(tenant, false, "security-officer");
    }

    /** 双人批准后仅生成 OMS DRAFT；TradingResearch 端口反射无 submit/placeOrder。 */
    @Test
    void approvedTradeCreatesDraftWithoutAnySubmitCapability() {
        // why：两人批准只授权生成草稿；反射检查进一步证明端口根本没有提交方法。
        String tenant = "regulated-trade-draft";
        AgentRun run = scenarios.start(tenant, RegulatedScenarioCatalog.TRADING, trade(), null);
        approveBoth(tenant, run.getId(), "approver-a", "approver-b");
        AgentRun completed = scenarios.continueRun(
                tenant, RegulatedScenarioCatalog.TRADING, run.getId());
        assertThat(completed.getStatus()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(artifact(tenant, run.getId(), "OMS_ORDER_DRAFT").getContent())
                .contains("\"status\":\"DRAFT\"").contains("\"submitted\":false")
                .contains("\"placeOrderCalled\":false").contains("\"submitCapability\":false")
                .contains("\"credentialsPresent\":false").contains("NO_OMS_ADAPTER_BY_DESIGN");
        assertThat(Arrays.stream(RegulatedScenarioPorts.TradingResearch.class.getMethods())
                .map(Method::getName))
                .noneMatch(name -> name.toLowerCase().contains("submit")
                        || name.toLowerCase().contains("placeorder"));
    }

    private void approveBoth(String tenant, String runId, String firstActor, String secondActor) {
        List<ApprovalRequest> requests = scenarios.approvalStage(tenant, runId);
        scenarios.decide(tenant, runId, requests.get(0).getId(), firstActor, "APPROVED", "ok");
        scenarios.decide(tenant, runId, requests.get(1).getId(), secondActor, "APPROVED", "ok");
    }

    private ScenarioArtifact artifact(String tenant, String runId, String type) {
        return scenarios.artifacts(tenant, runId).stream().filter(a -> type.equals(a.getType()))
                .findFirst().orElseThrow(AssertionError::new);
    }

    private static Map<String, Object> investigation(String narrative) {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("proposer", "risk-proposer");
        input.put("subjectRef", "fake-subject-001");
        input.put("narrative", narrative);
        input.put("requestedAction", "REVIEW");
        return input;
    }

    private static Map<String, Object> trade() {
        Map<String, Object> input = new LinkedHashMap<>();
        input.put("proposer", "trade-proposer");
        input.put("instrument", "AAPL");
        input.put("portfolioRef", "fake-portfolio-001");
        input.put("quantity", 100);
        input.put("marketDataAgeSeconds", 10);
        input.put("marketOpen", true);
        input.put("stressLossPct", 0.20);
        return input;
    }
}
