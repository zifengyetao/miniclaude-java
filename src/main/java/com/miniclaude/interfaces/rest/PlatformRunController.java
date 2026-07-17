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
 * 持久运行的 REST 控制面。
 *
 * <p>控制器只做租户边界、输入提取和命令转发；状态转换、幂等、审批参数绑定以及
 * 预算/超时判定由领域端口及其事务实现负责。查询和命令始终携带租户，避免仅凭全局 ID
 * 越权访问运行历史。</p>
 */
@RestController
@RequestMapping("/api/v1/platform/runs")
public class PlatformRunController {

    private final AgentPlatformService platform;
    private final DurableOrchestrator orchestrator;
    private final DurableStores.RunEventStore events;
    private final DurableStores.CheckpointStore checkpoints;
    private final DurableStores.ApprovalService approvals;

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

    @GetMapping
    public List<AgentRun> list() {
        return platform.listRuns();
    }

    @GetMapping("/{id}")
    public AgentRun get(@PathVariable String id) {
        return platform.getRun(id);
    }

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

    @GetMapping("/{id}/events")
    public List<RunEvent> events(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String id) {
        return events.findEvents(tenantId, id);
    }

    @GetMapping("/{id}/checkpoints")
    public List<RunCheckpoint> checkpoints(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String id) {
        return checkpoints.findCheckpoints(tenantId, id);
    }

    @GetMapping("/{id}/approvals")
    public List<ApprovalRequest> approvals(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String id) {
        return approvals.findApprovals(tenantId, id);
    }

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

    @PostMapping("/{id}/pause")
    public AgentRun pause(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return orchestrator.pause(tenantId, id, key(key));
    }

    @PostMapping("/{id}/resume")
    public AgentRun resume(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return orchestrator.resume(tenantId, id, key(key));
    }

    @PostMapping("/{id}/cancel")
    public AgentRun cancel(
            @RequestHeader(value = "X-Tenant-Id", defaultValue = "default") String tenantId,
            @PathVariable String id,
            @RequestHeader(value = "Idempotency-Key", required = false) String key) {
        return orchestrator.cancel(tenantId, id, key(key));
    }

    private static String required(Map<String, Object> body, String name) {
        Object value = body.get(name);
        if (value == null || value.toString().trim().isEmpty()) {
            throw new IllegalArgumentException(name + " required");
        }
        return value.toString();
    }

    private static String key(String key) {
        // 无键请求仍可执行，但只有调用方复用同一非空键时，跨请求重试才具备幂等语义。
        return key == null || key.trim().isEmpty() ? UUID.randomUUID().toString() : key;
    }
}
