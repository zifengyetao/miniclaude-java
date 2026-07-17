package com.miniclaude.infrastructure.durable;

import com.miniclaude.application.platform.AgentPlatformService;
import com.miniclaude.domain.durable.ApprovalRequest;
import com.miniclaude.domain.durable.DurableOrchestrator;
import com.miniclaude.domain.durable.DurableStores;
import com.miniclaude.domain.platform.AgentDefinition;
import com.miniclaude.domain.platform.AgentRun;
import com.miniclaude.domain.platform.ExecutionMode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:durable-test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "platform.orchestrator=local"
})
class DurableExecutionTest {
    @Autowired DurableOrchestrator orchestrator;
    @Autowired DurableStores.RunEventStore events;
    @Autowired DurableStores.CheckpointStore checkpoints;
    @Autowired DurableStores.ApprovalService approvals;
    @Autowired AgentPlatformService platform;

    private String agentId;

    @BeforeEach
    void agent() {
        agentId = platform.listAgents().stream()
                .filter(a -> a.getExecutionModes().contains(ExecutionMode.PLAN_EXECUTE))
                .map(AgentDefinition::getId).findFirst().orElseThrow(AssertionError::new);
    }

    @Test
    void resumesFromPersistedCheckpoint() {
        AgentRun run = create(new BigDecimal("10"));
        orchestrator.recordStep("tenant-a", run.getId(), "prepare", "{\"done\":true}",
                BigDecimal.ZERO, "step-1");
        orchestrator.pause("tenant-a", run.getId(), "pause-1");
        AgentRun resumed = orchestrator.resume("tenant-a", run.getId(), "resume-1");

        assertThat(resumed.getStatus()).isEqualTo(AgentRun.Status.RUNNING);
        assertThat(checkpoints.latest("tenant-a", run.getId())).get()
                .extracting(cp -> cp.getStepId()).isEqualTo("prepare");
    }

    @Test
    void deduplicatesEventsAndTransitions() {
        AgentRun run = create(new BigDecimal("10"));
        orchestrator.pause("tenant-a", run.getId(), "same-pause");
        orchestrator.pause("tenant-a", run.getId(), "same-pause");

        assertThat(events.findEvents("tenant-a", run.getId()).stream()
                .filter(e -> e.getIdempotencyKey().equals("same-pause"))).hasSize(1);
    }

    @Test
    void approvalIsBoundToExactActionParametersAndFailsClosed() {
        AgentRun run = create(new BigDecimal("10"));
        ApprovalRequest approval = orchestrator.awaitApproval("tenant-a", run.getId(), "deploy",
                "DEPLOY", "{\"environment\":\"prod\"}", Duration.ofMinutes(5), "wait-1");

        assertThatThrownBy(() -> approvals.decide("tenant-a", approval.getId(),
                "{\"environment\":\"dev\"}", ApprovalRequest.Status.APPROVED, "operator", "ok"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("parameters changed");

        ApprovalRequest decided = approvals.decide("tenant-a", approval.getId(),
                "{\"environment\":\"prod\"}", ApprovalRequest.Status.APPROVED, "operator", "ok");
        assertThat(decided.getStatus()).isEqualTo(ApprovalRequest.Status.APPROVED);
        assertThat(orchestrator.resume("tenant-a", run.getId(), "resume-approved").getStatus())
                .isEqualTo(AgentRun.Status.RUNNING);
    }

    @Test
    void terminatesWhenCostBudgetIsExceeded() {
        AgentRun run = create(new BigDecimal("0.50"));
        AgentRun stopped = orchestrator.recordStep("tenant-a", run.getId(), "costly", "{}",
                new BigDecimal("0.51"), "cost-1");

        assertThat(stopped.getStatus()).isEqualTo(AgentRun.Status.BUDGET_EXCEEDED);
        assertThat(stopped.getCostUsd()).isEqualByComparingTo("0.51");
    }

    private AgentRun create(BigDecimal budget) {
        return orchestrator.create("tenant-a", agentId, ExecutionMode.PLAN_EXECUTE, "test goal",
                10, budget, Duration.ofMinutes(10));
    }
}
