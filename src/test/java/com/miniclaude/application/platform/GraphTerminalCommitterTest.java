package com.miniclaude.application.platform;

import com.miniclaude.domain.durable.DurableOrchestrator;
import com.miniclaude.domain.durable.DurableStores;
import com.miniclaude.domain.platform.AgentDefinition;
import com.miniclaude.domain.platform.AgentRun;
import com.miniclaude.domain.platform.ExecutionMode;
import com.miniclaude.domain.scenario.ScenarioArtifact;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 验证终态制品不会早于 SUCCEEDED 对外可见。 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:graph-terminal;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "platform.orchestrator=local"
})
class GraphTerminalCommitterTest {
    @Autowired GraphTerminalCommitter committer;
    @Autowired DurableOrchestrator orchestrator;
    @Autowired DurableStores.CheckpointStore checkpoints;
    @Autowired ScenarioArtifact.Repository artifacts;
    @Autowired AgentPlatformService platform;

    @Test
    void rollsBackArtifactWhenTerminalRunCommitFails() {
        String agentId = platform.listAgents().stream()
                .filter(agent -> agent.getExecutionModes().contains(ExecutionMode.GRAPH))
                .map(AgentDefinition::getId)
                .findFirst().orElseThrow(AssertionError::new);
        AgentRun run = orchestrator.create("tenant-a", agentId, ExecutionMode.GRAPH, "terminal",
                10, new BigDecimal("0.50"), Duration.ofMinutes(5));

        assertThatThrownBy(() -> committer.commit(
                "tenant-a", run.getId(), "report", "{\"_nextNode\":null}",
                new BigDecimal("0.51"), "terminal-over-budget",
                () -> artifacts.saveIdempotent(
                        "tenant-a", run.getId(), "REPORT", "report.json", "{}",
                        "terminal-report")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stopped before completion");

        assertThat(platform.getRun(run.getId()).getStatus()).isEqualTo(AgentRun.Status.PENDING);
        assertThat(checkpoints.findCheckpoints("tenant-a", run.getId())).isEmpty();
        assertThat(artifacts.findByRun("tenant-a", run.getId())).isEmpty();
    }
}
