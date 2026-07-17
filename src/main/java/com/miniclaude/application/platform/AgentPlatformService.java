package com.miniclaude.application.platform;

import com.miniclaude.domain.platform.AgentDefinition;
import com.miniclaude.domain.platform.AgentDefinitionRepository;
import com.miniclaude.domain.platform.AgentRun;
import com.miniclaude.domain.platform.AgentRunRepository;
import com.miniclaude.domain.platform.ExecutionMode;
import com.miniclaude.domain.durable.DurableOrchestrator;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;
import java.time.Duration;

@Service
public class AgentPlatformService {

    private final AgentDefinitionRepository definitions;
    private final AgentRunRepository runs;
    private final DurableOrchestrator orchestrator;

    public AgentPlatformService(
            AgentDefinitionRepository definitions,
            AgentRunRepository runs,
            DurableOrchestrator orchestrator) {
        this.definitions = definitions;
        this.runs = runs;
        this.orchestrator = orchestrator;
    }

    @Transactional
    public AgentDefinition createAgent(
            String name,
            String description,
            String roleName,
            AgentDefinition.RiskLevel riskLevel,
            AgentDefinition.EvolutionLevel evolutionLevel,
            Set<ExecutionMode> executionModes) {
        return definitions.save(AgentDefinition.draft(
                name,
                description,
                roleName,
                riskLevel,
                evolutionLevel,
                executionModes));
    }

    public List<AgentDefinition> listAgents() {
        return definitions.findAll();
    }

    public AgentDefinition getAgent(String id) {
        return definitions.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("agent not found: " + id));
    }

    @Transactional
    public AgentRun startRun(
            String agentId,
            ExecutionMode executionMode,
            String goal,
            Integer maxSteps,
            BigDecimal maxCostUsd) {
        AgentDefinition agent = getAgent(agentId);
        if (!agent.getExecutionModes().contains(executionMode)) {
            throw new IllegalArgumentException(
                    "agent does not support execution mode: " + executionMode);
        }
        int boundedMaxSteps = maxSteps == null ? 24 : Math.min(Math.max(maxSteps, 1), 200);
        return runs.save(AgentRun.pending(
                agentId,
                executionMode,
                goal,
                boundedMaxSteps,
                maxCostUsd));
    }

    public List<AgentRun> listRuns() {
        return runs.findAll();
    }

    @Transactional
    public AgentRun startDurableRun(String tenantId, String agentId, ExecutionMode executionMode,
                                    String goal, Integer maxSteps, BigDecimal maxCostUsd,
                                    Long timeoutSeconds) {
        AgentDefinition agent = getAgent(agentId);
        if (!agent.getExecutionModes().contains(executionMode)) {
            throw new IllegalArgumentException(
                    "agent does not support execution mode: " + executionMode);
        }
        int bounded = maxSteps == null ? 24 : Math.min(Math.max(maxSteps, 1), 200);
        long timeout = timeoutSeconds == null ? 3600 : timeoutSeconds;
        return orchestrator.create(tenantId, agentId, executionMode, goal, bounded, maxCostUsd,
                Duration.ofSeconds(timeout));
    }

    public AgentRun getRun(String id) {
        return runs.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("run not found: " + id));
    }
}
