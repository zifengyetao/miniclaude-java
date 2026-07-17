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
class RegulatedScenariosE2ETest {
    @Autowired RegulatedScenarioService scenarios;
    @Autowired MockMvc mvc;

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

    @Test
    void investigationProducesMaskedVerifiedSuggestionAfterFourEyes() {
        String tenant = "regulated-investigation";
        Map<String, Object> input = investigation("reviewer@example.com 13800138000");
        AgentRun waiting = scenarios.start(tenant, RegulatedScenarioCatalog.INVESTIGATION,
                input, "investigation-idempotency");
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

    @Test
    void blocksAutomaticAdverseDecision() {
        Map<String, Object> input = investigation("masked");
        input.put("requestedAction", "BAN");
        AgentRun run = scenarios.start("regulated-auto-block",
                RegulatedScenarioCatalog.INVESTIGATION, input, null);
        assertThat(run.getStatus()).isEqualTo(AgentRun.Status.FAILED);
        assertThat(artifact("regulated-auto-block", run.getId(), "SAFETY_BLOCK").getContent())
                .contains("automatic reject/deny/ban/freeze decision is forbidden");
    }

    @Test
    void deterministicRiskEngineBlocksLimitsAndStaleMarket() {
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

    @Test
    void oneApprovalIsInsufficient() {
        String tenant = "regulated-single-approval";
        AgentRun run = scenarios.start(tenant, RegulatedScenarioCatalog.TRADING, trade(), null);
        ApprovalRequest first = scenarios.approvalStage(tenant, run.getId()).get(0);
        scenarios.decide(tenant, run.getId(), first.getId(), "approver-a", "APPROVED", "ok");
        assertThatThrownBy(() -> scenarios.continueRun(
                tenant, RegulatedScenarioCatalog.TRADING, run.getId()))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("two distinct non-proposer approvals");
    }

    @Test
    void samePersonCannotApproveTwiceAndProposerCannotApprove() {
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

    @Test
    void killSwitchStopsNewRuns() {
        String tenant = "regulated-killed";
        scenarios.setKillSwitch(tenant, true, "security-officer");
        assertThatThrownBy(() -> scenarios.start(
                tenant, RegulatedScenarioCatalog.TRADING, trade(), null))
                .isInstanceOf(SecurityException.class).hasMessageContaining("kill switch");
        scenarios.setKillSwitch(tenant, false, "security-officer");
    }

    @Test
    void approvedTradeCreatesDraftWithoutAnySubmitCapability() {
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
