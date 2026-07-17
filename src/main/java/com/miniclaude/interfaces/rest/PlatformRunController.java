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
        if (!request.getRunId().equals(runId)) throw new IllegalArgumentException("approval not found");
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
        return key == null || key.trim().isEmpty() ? UUID.randomUUID().toString() : key;
    }
}
