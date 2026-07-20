package com.miniclaude.application.scenario;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.miniclaude.domain.durable.DurableStores;
import com.miniclaude.domain.durable.RunCheckpoint;
import com.miniclaude.domain.scenario.ScenarioArtifact;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Data Analyst v1 固定集的确定性 Eval Runner。
 *
 * <p>本测试不调用 LLM Judge，只评分状态、Graph 路径、审批和制品安全契约。每条样本创建独立 Run，
 * 失败输出带样本 ID，适合作为后续 Release Gate 的 deterministic scorer 基线。</p>
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:data-analyst-eval;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "platform.orchestrator=local"
})
@AutoConfigureMockMvc
class DataAnalystEvalDatasetTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired DurableStores.CheckpointStore checkpoints;
    @Autowired DurableStores.ApprovalService approvals;
    @Autowired ScenarioArtifact.Repository artifacts;

    @Test
    void deterministicDatasetPassesStatePathApprovalAndArtifactScorers() throws Exception {
        List<JsonNode> samples = loadDataset();
        assertThat(samples).hasSize(30);

        for (JsonNode sample : samples) {
            String sampleId = sample.get("id").asText();
            JsonNode expected = sample.get("expected");
            JsonNode run = start(sample.get("input"));
            String runId = run.get("id").asText();

            assertThat(run.get("status").asText())
                    .as("%s status", sampleId)
                    .isEqualTo(expected.get("status").asText());

            List<String> actualPath = checkpoints.findCheckpoints("default", runId).stream()
                    .map(RunCheckpoint::getStepId)
                    .filter(step -> !"terminal".equals(step) && !"failure".equals(step))
                    .collect(Collectors.toList());
            List<String> expectedPath = new ArrayList<>();
            expected.get("path").forEach(node -> expectedPath.add(node.asText()));
            assertThat(actualPath).as("%s graph path", sampleId).containsExactlyElementsOf(expectedPath);

            boolean approvalExpected = expected.get("approval").asBoolean();
            assertThat(!approvals.findApprovals("default", runId).isEmpty())
                    .as("%s approval", sampleId)
                    .isEqualTo(approvalExpected);

            List<ScenarioArtifact> runArtifacts = artifacts.findByRun("default", runId);
            JsonNode expectedArtifact = expected.get("artifact");
            if (expectedArtifact == null || expectedArtifact.isNull()) {
                assertThat(runArtifacts).as("%s no report", sampleId)
                        .noneMatch(artifact -> "REPORT".equals(artifact.getType()));
            } else {
                assertThat(runArtifacts).as("%s artifact", sampleId)
                        .anyMatch(artifact -> expectedArtifact.asText().equals(artifact.getType()));
            }

            JsonNode reason = expected.get("reasonContains");
            if (reason != null && !reason.isNull()) {
                assertThat(runArtifacts.stream()
                        .filter(artifact -> "SAFETY_BLOCK".equals(artifact.getType()))
                        .map(ScenarioArtifact::getContent)
                        .collect(Collectors.joining("\n")))
                        .as("%s failure reason", sampleId)
                        .contains(reason.asText());
            }
        }
    }

    private JsonNode start(JsonNode input) throws Exception {
        String response = mvc.perform(post("/api/v1/scenarios/data-analyst/start")
                        .contentType("application/json").content(input.toString()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private List<JsonNode> loadDataset() throws Exception {
        InputStream stream = getClass().getResourceAsStream("/eval/data-analyst-v1.jsonl");
        if (stream == null) throw new IllegalStateException("eval dataset missing");
        List<JsonNode> samples = new ArrayList<>();
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (!line.trim().isEmpty()) samples.add(json.readTree(line));
            }
        }
        return samples;
    }
}
