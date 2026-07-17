package com.miniclaude.application.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniclaude.domain.durable.ApprovalRequest;
import com.miniclaude.domain.durable.DurableStores;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:pilot-scenarios;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "platform.orchestrator=local"
})
@AutoConfigureMockMvc
class PilotScenariosE2ETest {
    private static final Path WORKSPACE = createWorkspace();

    @DynamicPropertySource
    static void workspace(DynamicPropertyRegistry registry) {
        registry.add("platform.sandbox.allowed-workspaces", () -> WORKSPACE.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DurableStores.ApprovalService approvals;

    @Test
    void exposesThreePreciselyVersionedValidTemplates() throws Exception {
        mvc.perform(get("/api/v1/scenarios/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].version").value("1.0.0"))
                .andExpect(jsonPath("$[0].agentSpec.version").value("1.0.0"))
                .andExpect(jsonPath("$[0].graph.entryNode").isNotEmpty());
    }

    @Test
    void codingAgentCompletesWithFakePatchAndPrDraft() throws Exception {
        String body = "{\"workspace\":\"" + escape(WORKSPACE.toString())
                + "\",\"goal\":\"add safe feature\",\"branch\":\"feature/safe\"}";
        JsonNode run = start("coding-agent", body);
        assertThat(run.get("status").asText()).isEqualTo("SUCCEEDED");

        mvc.perform(get("/api/v1/scenarios/coding-agent/runs/{id}/artifacts", run.get("id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'PATCH_PROPOSAL')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.type == 'PR_DRAFT')]").isNotEmpty())
                .andExpect(jsonPath("$[?(@.type == 'PR_DRAFT')].content").value(
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("\"externalPrCreated\":false"))));
    }

    @Test
    void codingAgentBlocksMainAndBypassFlags() throws Exception {
        JsonNode main = start("coding-agent", "{\"workspace\":\"" + escape(WORKSPACE.toString())
                + "\",\"goal\":\"change\",\"branch\":\"main\"}");
        JsonNode bypass = start("coding-agent", "{\"workspace\":\"" + escape(WORKSPACE.toString())
                + "\",\"goal\":\"git commit --no-verify\",\"branch\":\"feature/x\"}");
        assertThat(main.get("status").asText()).isEqualTo("FAILED");
        assertThat(bypass.get("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void analystCompletesReadOnlyReportAndBlocksUnsafeSql() throws Exception {
        JsonNode success = start("data-analyst",
                "{\"goal\":\"revenue\",\"metric\":\"net_revenue\","
                        + "\"sql\":\"SELECT sum(amount) FROM sales LIMIT 100\"}");
        assertThat(success.get("status").asText()).isEqualTo("SUCCEEDED");
        mvc.perform(get("/api/v1/scenarios/data-analyst/runs/{id}/artifacts", success.get("id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.type == 'REPORT')].content").value(
                        org.hamcrest.Matchers.hasItem(org.hamcrest.Matchers.containsString("\"externalDbConnected\":false"))));

        JsonNode blocked = start("data-analyst",
                "{\"metric\":\"x\",\"sql\":\"SELECT * FROM users; DELETE FROM users LIMIT 10\"}");
        assertThat(blocked.get("status").asText()).isEqualTo("FAILED");
    }

    @Test
    void expensiveAnalyticsStopsAtApprovalThenContinues() throws Exception {
        JsonNode waiting = start("data-analyst",
                "{\"metric\":\"costly\",\"sql\":\"SELECT * FROM expensive_table LIMIT 10\"}");
        assertThat(waiting.get("status").asText()).isEqualTo("WAITING_APPROVAL");
        ApprovalRequest approval = approvals.findApprovals("default", waiting.get("id").asText()).get(0);
        approvals.decide("default", approval.getId(), approval.getActionParameters(),
                ApprovalRequest.Status.APPROVED, "test-operator", "approved in test");

        mvc.perform(post("/api/v1/scenarios/data-analyst/runs/{id}/continue", waiting.get("id").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("SUCCEEDED"));
    }

    @Test
    void supportMasksPiiNeverSendsAndHandsSensitiveIntentToHuman() throws Exception {
        JsonNode normal = start("customer-support",
                "{\"message\":\"请联系 test@example.com 查询订单\",\"confidence\":0.95}");
        assertThat(normal.get("status").asText()).isEqualTo("SUCCEEDED");
        String artifactsJson = mvc.perform(get("/api/v1/scenarios/customer-support/runs/{id}/artifacts",
                        normal.get("id").asText())).andReturn().getResponse().getContentAsString();
        assertThat(artifactsJson).contains("[PII]").contains("\\\"sent\\\":false")
                .contains("\\\"externalCrmConnected\\\":false").doesNotContain("test@example.com");

        JsonNode sensitive = start("customer-support",
                "{\"message\":\"我要投诉并起诉\",\"confidence\":0.99}");
        assertThat(sensitive.get("status").asText()).isEqualTo("WAITING_APPROVAL");
        assertThat(approvals.findApprovals("default", sensitive.get("id").asText()).get(0)
                .getActionType()).isEqualTo("HUMAN_SUPPORT_HANDOFF");
    }

    private JsonNode start(String scenario, String body) throws Exception {
        String response = mvc.perform(post("/api/v1/scenarios/{scenario}/start", scenario)
                        .contentType("application/json").content(body))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private static Path createWorkspace() {
        try {
            return Files.createTempDirectory("pilot-fake-git-");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static String escape(String value) { return value.replace("\\", "\\\\").replace("\"", "\\\""); }
}
