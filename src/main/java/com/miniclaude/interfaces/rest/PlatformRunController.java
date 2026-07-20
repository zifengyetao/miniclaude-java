package com.miniclaude.interfaces.rest;

import com.miniclaude.application.platform.AgentPlatformService;
import com.miniclaude.domain.platform.AgentRun;
import com.miniclaude.domain.durable.ApprovalRequest;
import com.miniclaude.domain.durable.DurableOrchestrator;
import com.miniclaude.domain.durable.DurableStores;
import com.miniclaude.domain.durable.RunCheckpoint;
import com.miniclaude.domain.durable.RunEvent;
import com.miniclaude.interfaces.rest.dto.CreateAgentRunRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestHeader;

import javax.validation.Valid;
import java.util.List;
import java.util.Map;
import java.time.Duration;
import java.util.UUID;

/**
 * 持久化 Agent Run 的 REST 控制面。
 *
 * <p><b>职责</b>：租户边界提取、命令转发与只读查询；状态机、幂等、审批参数绑定及
 * 预算/超时判定由 {@link DurableOrchestrator} 与领域存储实现。
 * <p><b>上游</b>：平台运维/自动化客户端，通过 {@code X-Tenant-Id} 声明租户。
 * <b>下游</b>：{@link AgentPlatformService}、{@link DurableOrchestrator}、
 * {@link DurableStores} 各端口。
 * <p><b>安全/约束</b>：查询与命令始终携带租户，避免仅凭全局 runId 越权访问历史；
 * 审批决策需回传精确动作参数哈希比对（fail-closed）；pause/resume/cancel 支持
 * {@code Idempotency-Key} 头实现跨请求重试幂等。
 */
@RestController
@RequestMapping("/api/v1/platform/runs")
public class PlatformRunController {

    private final AgentPlatformService platform;
    private final DurableOrchestrator orchestrator;
    private final DurableStores.RunEventStore events;
    private final DurableStores.CheckpointStore checkpoints;
    private final DurableStores.ApprovalService approvals;

    /**
     * @param platform     运行创建与查询
     * @param orchestrator 暂停/恢复/取消/审批编排
     * @param events       运行事件只读存储
     * @param checkpoints  检查点只读存储
     * @param approvals    审批请求读写
     */
    public PlatformRunController(AgentPlatformService platform, DurableOrchestrator orchestrator,
                                 DurableStores.RunEventStore events,
                                 DurableStores.CheckpointStore checkpoints,
                                 DurableStores.ApprovalService approvals) {
        this.platform = platform;
        this.orchestrator = orchestrator;
        this.events = events;
        this.checkpoints = checkpoints;
        this.approvals = approvals;
    }

    /** @return 全部 Run 列表（当前无租户过滤，依赖仓储实现） */
    @GetMapping
    public List<AgentRun> list() {
        return platform.listRuns();
    }

    /**
     * @param id Run 主键
     * @return Run 快照
     * @throws IllegalArgumentException 不存在时
     */
    @GetMapping("/{id}")
    public AgentRun get(@PathVariable String id) {
        return platform.getRun(id);
    }

    /**
     * 创建新的持久化 Run。
     *
     * @param tenantId 租户 ID，缺省 {@code default}
     * @param request  运行目标、模式及资源上限
     * @return 新建 Run，HTTP 201
     * @implNote 步数/成本/超时在创建时固化，恢复后仍继续生效
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public AgentRun create(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @Valid @RequestBody CreateAgentRunRequest request) {
        // 步骤、成本和超时是持久运行的硬边界，在创建时一次性固化，恢复后仍继续生效。
        return platform.startDurableRun(
                tenantId,
                request.getAgentId(),
                request.getExecutionMode(),
                request.getGoal(),
                request.getMaxSteps(),
                request.getMaxCostUsd(),
                request.getTimeoutSeconds());
    }

    /**
     * 查询 Run 的事件流（审计/调试）。
     *
     * @param tenantId 租户边界
     * @param id       Run ID
     * @return 按时间排序的事件列表
     */
    @GetMapping("/{id}/events")
    public List<RunEvent> events(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String id) {
        return events.findEvents(tenantId, id);
    }

    /**
     * 查询 Run 的检查点（恢复点）。
     *
     * @param tenantId 租户边界
     * @param id       Run ID
     */
    @GetMapping("/{id}/checkpoints")
    public List<RunCheckpoint> checkpoints(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String id) {
        return checkpoints.findCheckpoints(tenantId, id);
    }

    /**
     * 列出 Run 关联的全部审批请求。
     *
     * @param tenantId 租户边界
     * @param id       Run ID
     */
    @GetMapping("/{id}/approvals")
    public List<ApprovalRequest> approvals(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String id) {
        return approvals.findApprovals(tenantId, id);
    }

    /**
     * 为 Run 的某步骤发起人工审批等待。
     *
     * @param tenantId 租户
     * @param id       Run ID
     * @param body     须含 {@code stepId}、{@code actionType}、{@code actionParameters}；
     *                 可选 {@code ttlSeconds}（默认 900）、{@code idempotencyKey}
     * @return 新建的 {@link ApprovalRequest}，HTTP 201
     * @throws IllegalArgumentException 必填字段缺失
     */
    @PostMapping("/{id}/approvals")
    @ResponseStatus(HttpStatus.CREATED)
    public ApprovalRequest requestApproval(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String id, @RequestBody Map<String, Object> body) {
        String stepId = required(body, "stepId");
        String actionType = required(body, "actionType");
        String parameters = required(body, "actionParameters");
        long ttl = body.get("ttlSeconds") instanceof Number
                ? ((Number) body.get("ttlSeconds")).longValue() : 900;
        // 客户端可提供稳定幂等键以安全重试；缺失时生成键只保证本次请求内部唯一。
        String key = body.get("idempotencyKey") == null ? UUID.randomUUID().toString()
                : body.get("idempotencyKey").toString();
        return orchestrator.awaitApproval(tenantId, id, stepId, actionType, parameters,
                Duration.ofSeconds(ttl), key);
    }

    /**
     * 对审批请求做出批准或拒绝决定。
     *
     * @param tenantId   租户
     * @param runId      路径中的 Run ID（须与审批记录一致）
     * @param approvalId 审批主键
     * @param body       须含 {@code actionParameters}（与申请时哈希一致）、{@code decision}、
     *                   {@code actor}；可选 {@code reason}
     * @return 更新后的审批记录
     * @throws IllegalArgumentException 审批不存在或 runId 不匹配
     * @implNote 存储层比较申请时的参数哈希，参数变化即 fail-closed
     */
    @PostMapping("/{runId}/approvals/{approvalId}/decision")
    public ApprovalRequest decideApproval(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String runId, @PathVariable String approvalId,
            @RequestBody Map<String, Object> body) {
        ApprovalRequest request = approvals.find(tenantId, approvalId)
                .orElseThrow(() -> new IllegalArgumentException("approval not found"));
        // 同时校验租户和路径中的 runId，避免把一个运行的批准提交给另一个运行。
        if (!request.getRunId().equals(runId)) throw new IllegalArgumentException("approval not found");
        // 客户端必须回传精确动作参数；存储层比较申请时的哈希，参数变化即 fail-closed。
        return approvals.decide(tenantId, approvalId, required(body, "actionParameters"),
                ApprovalRequest.Status.valueOf(required(body, "decision")),
                required(body, "actor"), body.get("reason") == null ? "" : body.get("reason").toString());
    }

    /**
     * 暂停 Run 执行。
     *
     * @param key 可选 {@code Idempotency-Key}，缺失时内部生成一次性键
     */
    @PostMapping("/{id}/pause")
    public AgentRun pause(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return orchestrator.pause(tenantId, id, key(key));
    }

    /** 恢复已暂停的 Run。 */
    @PostMapping("/{id}/resume")
    public AgentRun resume(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return orchestrator.resume(tenantId, id, key(key));
    }

    /** 取消 Run（终态）。 */
    @PostMapping("/{id}/cancel")
    public AgentRun cancel(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return orchestrator.cancel(tenantId, id, key(key));
    }

    /**
     * 从 JSON body 提取非空字符串字段。
     *
     * @throws IllegalArgumentException 缺失或空白
     */
    private static String required(Map<String, Object> body, String name) {
        Object value = body.get(name);
        if (value == null || value.toString().trim().isEmpty()) {
            throw new IllegalArgumentException(name + " required");
        }
        return value.toString();
    }

    /**
     * 规范化幂等键：空白时生成 UUID，保证编排器总能收到非空键。
     *
     * @implNote 无键请求仍可执行，但只有调用方复用同一非空键时，跨请求重试才具备幂等语义
     */
    private static String key(String key) {
        // 无键请求仍可执行，但只有调用方复用同一非空键时，跨请求重试才具备幂等语义。
        return key == null || key.trim().isEmpty() ? UUID.randomUUID().toString() : key;
    }
}
