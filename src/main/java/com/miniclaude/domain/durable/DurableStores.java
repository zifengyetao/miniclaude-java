package com.miniclaude.domain.durable;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

public final class DurableStores {
    private DurableStores() {}

    public interface RunEventStore {
        RunEvent append(String tenantId, String runId, String stepId, String type,
                        String idempotencyKey, String payload);
        List<RunEvent> findEvents(String tenantId, String runId);
    }

    public interface CheckpointStore {
        RunCheckpoint save(String tenantId, String runId, String stepId, String state);
        Optional<RunCheckpoint> latest(String tenantId, String runId);
        List<RunCheckpoint> findCheckpoints(String tenantId, String runId);
    }

    public interface ApprovalService {
        ApprovalRequest request(String tenantId, String runId, String stepId, String actionType,
                                String actionParameters, Duration ttl);
        ApprovalRequest decide(String tenantId, String approvalId, String expectedActionParameters,
                               ApprovalRequest.Status decision, String actor, String reason);
        Optional<ApprovalRequest> find(String tenantId, String approvalId);
        List<ApprovalRequest> findApprovals(String tenantId, String runId);
    }
}
