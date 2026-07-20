package com.miniclaude.application.platform;

import com.miniclaude.domain.durable.DurableOrchestrator;
import com.miniclaude.domain.platform.AgentRun;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;

/**
 * 将本地数据库终态制品与 Graph TERMINAL checkpoint/SUCCEEDED 原子提交。
 *
 * <p>terminalAction 只能写入与主数据库同事务资源（例如 ScenarioArtifact），禁止执行网络、
 * Shell 或其他无法回滚的外部副作用。</p>
 */
@Service
public class GraphTerminalCommitter {
    private final DurableOrchestrator orchestrator;

    public GraphTerminalCommitter(DurableOrchestrator orchestrator) {
        this.orchestrator = orchestrator;
    }

    @Transactional
    public AgentRun commit(String tenantId, String runId, String stepId, String state,
                           BigDecimal stepCostUsd, String idempotencyKey,
                           GraphRunner.TerminalAction terminalAction) {
        if (terminalAction != null) terminalAction.run();
        return orchestrator.recordTerminalStep(
                tenantId, runId, stepId, state, stepCostUsd, idempotencyKey);
    }
}
