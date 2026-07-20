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

/**
 * 本地持久执行的集成契约测试。
 *
 * <p>使用真实 Spring 事务、Flyway 表结构和 JDBC 存储验证恢复、幂等、审批 fail-closed
 * 以及预算终止；这些断言保护的是跨组件持久语义，而非单个方法的实现细节。</p>
 */
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

    /** pause 后 resume 应恢复 RUNNING，且 latest checkpoint 指向 prepare 步骤。 */
    @Test
    void resumesFromPersistedCheckpoint() {
        // 先完成步骤并暂停，再恢复；checkpoint 必须独立于进程内控制流保留恢复位置。
        AgentRun run = create(new BigDecimal("10"));
        orchestrator.recordStep("tenant-a", run.getId(), "prepare", "{\"done\":true}",
                BigDecimal.ZERO, "step-1");
        orchestrator.pause("tenant-a", run.getId(), "pause-1");
        AgentRun resumed = orchestrator.resume("tenant-a", run.getId(), "resume-1");

        assertThat(resumed.getStatus()).isEqualTo(AgentRun.Status.RUNNING);
        assertThat(checkpoints.latest("tenant-a", run.getId())).get()
                .extracting(cp -> cp.getStepId()).isEqualTo("prepare");
    }

    /** 相同 idempotencyKey 重复 pause 只应写入一条 RUN 事件。 */
    @Test
    void deduplicatesEventsAndTransitions() {
        // 模拟客户端因响应丢失而重发同一命令，相同幂等键只能产生一条运行事实。
        AgentRun run = create(new BigDecimal("10"));
        orchestrator.pause("tenant-a", run.getId(), "same-pause");
        orchestrator.pause("tenant-a", run.getId(), "same-pause");

        assertThat(events.findEvents("tenant-a", run.getId()).stream()
                .filter(e -> e.getIdempotencyKey().equals("same-pause"))).hasSize(1);
    }

    /** 批准时 actionParameters 与 await 时不一致应拒绝；原参数批准后可 resume。 */
    @Test
    void approvalIsBoundToExactActionParametersAndFailsClosed() {
        // 先用篡改后的参数尝试批准，验证哈希绑定拒绝授权；原参数随后仍可正常决定。
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

    /** 单步 cost 超过 maxCostUsd 预算时运行应进入 BUDGET_EXCEEDED 终态。 */
    @Test
    void terminatesWhenCostBudgetIsExceeded() {
        // 成本在步骤提交事务中累计；越界步骤被记账，同时运行进入不可继续的预算终态。
        AgentRun run = create(new BigDecimal("0.50"));
        AgentRun stopped = orchestrator.recordStep("tenant-a", run.getId(), "costly", "{}",
                new BigDecimal("0.51"), "cost-1");

        assertThat(stopped.getStatus()).isEqualTo(AgentRun.Status.BUDGET_EXCEEDED);
        assertThat(stopped.getCostUsd()).isEqualByComparingTo("0.51");
    }

    /** 审批节点的 checkpoint、WAITING 状态和 Approval 必须原子形成且可幂等重放。 */
    @Test
    void atomicallyRecordsApprovalStepAndRequest() {
        AgentRun run = create(new BigDecimal("10"));
        AgentRun waiting = orchestrator.recordStepAndAwaitApproval(
                "tenant-a", run.getId(), "approval", "{\"_nextNode\":\"query\"}",
                BigDecimal.ZERO, "ANALYTICS_QUERY_COST", "{\"sqlHash\":\"abc\"}",
                Duration.ofMinutes(5), "approval-step-1");
        AgentRun replayed = orchestrator.recordStepAndAwaitApproval(
                "tenant-a", run.getId(), "approval", "{\"_nextNode\":\"query\"}",
                BigDecimal.ZERO, "ANALYTICS_QUERY_COST", "{\"sqlHash\":\"abc\"}",
                Duration.ofMinutes(5), "approval-step-1");

        assertThat(waiting.getStatus()).isEqualTo(AgentRun.Status.WAITING_APPROVAL);
        assertThat(replayed.getStatus()).isEqualTo(AgentRun.Status.WAITING_APPROVAL);
        assertThat(checkpoints.findCheckpoints("tenant-a", run.getId())).hasSize(1);
        assertThat(approvals.findApprovals("tenant-a", run.getId())).hasSize(1);
    }

    /** 审批创建失败时，前置 Step/Checkpoint/状态更新必须整体回滚。 */
    @Test
    void rollsBackApprovalStepWhenApprovalRequestFails() {
        AgentRun run = create(new BigDecimal("10"));

        assertThatThrownBy(() -> orchestrator.recordStepAndAwaitApproval(
                "tenant-a", run.getId(), "approval", "{\"_nextNode\":\"query\"}",
                BigDecimal.ZERO, "ANALYTICS_QUERY_COST", "{\"sqlHash\":\"abc\"}",
                Duration.ZERO, "approval-step-invalid"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl");

        assertThat(platform.getRun(run.getId()).getStatus()).isEqualTo(AgentRun.Status.PENDING);
        assertThat(checkpoints.findCheckpoints("tenant-a", run.getId())).isEmpty();
        assertThat(approvals.findApprovals("tenant-a", run.getId())).isEmpty();
        assertThat(events.findEvents("tenant-a", run.getId())).hasSize(1);
    }

    /** TERMINAL 节点 checkpoint 与 SUCCEEDED 必须在同一编排命令中提交并可重放。 */
    @Test
    void atomicallyRecordsTerminalStepAndCompletion() {
        AgentRun run = create(new BigDecimal("10"));
        AgentRun completed = orchestrator.recordTerminalStep(
                "tenant-a", run.getId(), "report", "{\"_nextNode\":null}",
                BigDecimal.ZERO, "terminal-step-1");
        AgentRun replayed = orchestrator.recordTerminalStep(
                "tenant-a", run.getId(), "report", "{\"_nextNode\":null}",
                BigDecimal.ZERO, "terminal-step-1");

        assertThat(completed.getStatus()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(replayed.getStatus()).isEqualTo(AgentRun.Status.SUCCEEDED);
        assertThat(checkpoints.findCheckpoints("tenant-a", run.getId()).stream()
                .map(checkpoint -> checkpoint.getStepId()))
                .containsExactly("report", "terminal");
    }

    private AgentRun create(BigDecimal budget) {
        return orchestrator.create("tenant-a", agentId, ExecutionMode.PLAN_EXECUTE, "test goal",
                10, budget, Duration.ofMinutes(10));
    }
}
