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

/**
 * 数字员工定义与运行创建的应用服务。
 *
 * <p>本类编排领域对象、仓储和耐久调度器，负责执行模式与运行上限校验；它不执行 Agent，
 * 也不处理 HTTP 鉴权或 DTO 转换。写操作依赖 Spring 事务边界保证本地持久化一致性。
 */
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

    /**
     * 创建并保存草稿定义。
     *
     * <p>字段必须满足 {@link AgentDefinition} 的不变量。校验或持久化失败时事务回滚。
     * 该操作生成新标识，非幂等；并发请求会创建不同定义。
     */
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

    /** @return 全部 Agent 定义列表 */
    public List<AgentDefinition> listAgents() {
        return definitions.findAll();
    }

    /**
     * @param id 定义主键
     * @return 定义快照
     * @throws IllegalArgumentException 不存在时
     */
    public AgentDefinition getAgent(String id) {
        return definitions.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("agent not found: " + id));
    }

    /**
     * 创建普通待执行运行。
     *
     * <p>Agent 必须存在且声明支持目标模式。步数缺省为 24，并被限制在 1 到 200；
     * 无效 Agent、模式或领域参数会抛出异常，持久化失败时事务回滚。每次调用生成新运行，
     * 不提供幂等去重。
     */
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
        // 即使客户端绕过 DTO 校验，应用边界仍强制限制步数，避免无界资源消耗。
        int boundedMaxSteps = maxSteps == null ? 24 : Math.min(Math.max(maxSteps, 1), 200);
        return runs.save(AgentRun.pending(
                agentId,
                executionMode,
                goal,
                boundedMaxSteps,
                maxCostUsd));
    }

    /** @return 全部 Run 列表 */
    public List<AgentRun> listRuns() {
        return runs.findAll();
    }

    /**
     * 通过耐久调度器创建可恢复运行。
     *
     * <p>Agent 与模式前置条件同普通运行；超时缺省为一小时。调度器异常会向上传播，
     * 外部调度与数据库事务是否原子由调度实现决定。操作非幂等，并发调用可能创建多次运行。
     */
    @Transactional
    public AgentRun startDurableRun(String tenantId, String agentId, ExecutionMode executionMode,
                                    String goal, Integer maxSteps, BigDecimal maxCostUsd,
                                    Long timeoutSeconds) {
        AgentDefinition agent = getAgent(agentId);
        if (!agent.getExecutionModes().contains(executionMode)) {
            throw new IllegalArgumentException(
                    "agent does not support execution mode: " + executionMode);
        }
        // 在进入调度边界前统一收紧资源上限，不能信任外部请求直接给出的步数。
        int bounded = maxSteps == null ? 24 : Math.min(Math.max(maxSteps, 1), 200);
        long timeout = timeoutSeconds == null ? 3600 : timeoutSeconds;
        return orchestrator.create(tenantId, agentId, executionMode, goal, bounded, maxCostUsd,
                Duration.ofSeconds(timeout));
    }

    /**
     * @param id Run 主键
     * @return Run 快照
     * @throws IllegalArgumentException 不存在时
     */
    public AgentRun getRun(String id) {
        return runs.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("run not found: " + id));
    }
}
