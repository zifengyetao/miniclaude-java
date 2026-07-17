package com.miniclaude.infrastructure.temporal;

import io.temporal.activity.ActivityInterface;
import io.temporal.workflow.QueryMethod;
import io.temporal.workflow.SignalMethod;
import io.temporal.workflow.WorkflowInterface;
import io.temporal.workflow.WorkflowMethod;

/** Temporal 边界契约；业务层不依赖 Temporal 类型。 */
@WorkflowInterface
public interface DurableRunWorkflow {
    @WorkflowMethod
    void execute(Input input);

    @SignalMethod void pause();
    @SignalMethod void resume();
    @SignalMethod void cancel();
    @SignalMethod void approvalDecision(String approvalId, String decision, String actionHash);
    @QueryMethod Snapshot snapshot();

    final class Input {
        public String tenantId;
        public String runId;
        public int maxSteps;
        public long timeoutSeconds;
    }

    final class Snapshot {
        public String runId;
        public String status;
        public int currentStep;
        public String checkpointHash;
    }

    @ActivityInterface
    interface Activities {
        Snapshot load(String tenantId, String runId);
        void persistTransition(String tenantId, String runId, String eventType,
                               String idempotencyKey, String payload);
        void persistCheckpoint(String tenantId, String runId, String stepId, String state);
    }
}
