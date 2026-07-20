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
/**
 * 试点场景端到端安全契约测试。
 *
 * <p>测试使用 H2 和受控 fake，验证的是编排、隔离和阻断语义，不会访问真实 Git、
 * 数据库、CRM 或网络。关键断言同时证明终点只产生草稿/制品，而非外部副作用。</p>
 */
class PilotScenariosE2ETest {
    private static final Path WORKSPACE = createWorkspace();

    @DynamicPropertySource
    static void workspace(DynamicPropertyRegistry registry) {
        registry.add("platform.sandbox.allowed-workspaces", () -> WORKSPACE.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DurableStores.ApprovalService approvals;

    /** GET /scenarios/templates 应返回 3 个版本 pinned 的 RolePack。 */
    @Test
    void exposesThreePreciselyVersionedValidTemplates() throws Exception {
        mvc.perform(get("/api/v1/scenarios/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].version").value("1.0.0"))
                .andExpect(jsonPath("$[0].agentSpec.version").value("1.0.0"))
                .andExpect(jsonPath("$[0].graph.entryNode").isNotEmpty());
    }

    /** coding-agent 成功时应产出 PATCH_PROPOSAL/PR_DRAFT 且 externalPrCreated=false。 */
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

    /** main 分支与 --no-verify 类 goal 应 FAILED，阻断绕过审查链。 */
    @Test
    void codingAgentBlocksMainAndBypassFlags() throws Exception {
        // why：主分支直写和 --no-verify 都会绕开 Coding 场景的隔离审查链，必须失败。
        JsonNode main = start("coding-agent", "{\"workspace\":\"" + escape(WORKSPACE.toString())
                + "\",\"goal\":\"change\",\"branch\":\"main\"}");
        JsonNode bypass = start("coding-agent", "{\"workspace\":\"" + escape(WORKSPACE.toString())
                + "\",\"goal\":\"git commit --no-verify\",\"branch\":\"feature/x\"}");
        assertThat(main.get("status").asText()).isEqualTo("FAILED");
        assertThat(bypass.get("status").asText()).isEqualTo("FAILED");
    }

    /** 只读 SQL 成功生成 REPORT；多语句含 DELETE 应 FAILED。 */
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

        // 多语句中夹带 DELETE，用于证明只读 guard 不会只检查首个 SELECT。
        JsonNode blocked = start("data-analyst",
                "{\"metric\":\"x\",\"sql\":\"SELECT * FROM users; DELETE FROM users LIMIT 10\"}");
        assertThat(blocked.get("status").asText()).isEqualTo("FAILED");
    }

    /** 高成本查询 WAITING_APPROVAL，人工批准 continue 后 SUCCEEDED。 */
    @Test
    void expensiveAnalyticsStopsAtApprovalThenContinues() throws Exception {
        // fake 成本估算稳定触发人工审批；获批前查询节点不会执行。
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

    /** 客服场景 PII 掩码、永不 sent；高敏意图转 HUMAN_SUPPORT_HANDOFF 审批。 */
    @Test
    void supportMasksPiiNeverSendsAndHandsSensitiveIntentToHuman() throws Exception {
        // 正常路径也只能形成未发送草稿，且原始邮箱不能进入持久化制品。
        JsonNode normal = start("customer-support",
                "{\"message\":\"请联系 test@example.com 查询订单\",\"confidence\":0.95}");
        assertThat(normal.get("status").asText()).isEqualTo("SUCCEEDED");
        String artifactsJson = mvc.perform(get("/api/v1/scenarios/customer-support/runs/{id}/artifacts",
                        normal.get("id").asText())).andReturn().getResponse().getContentAsString();
        assertThat(artifactsJson).contains("[PII]").contains("\\\"sent\\\":false")
                .contains("\\\"externalCrmConnected\\\":false").doesNotContain("test@example.com");

        // 敏感投诉即使置信度很高也必须转人工，不能由高分覆盖合规规则。
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
