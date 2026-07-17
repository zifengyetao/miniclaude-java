package com.miniclaude.infrastructure.durable;

import com.miniclaude.domain.durable.ApprovalRequest;
import com.miniclaude.domain.durable.DurableOrchestrator;
import com.miniclaude.domain.durable.DurableStores;
import com.miniclaude.domain.platform.AgentRun;
import com.miniclaude.domain.platform.AgentRunRepository;
import com.miniclaude.domain.platform.ExecutionMode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

@Service
@ConditionalOnProperty(name = "platform.orchestrator", havingValue = "local", matchIfMissing = true)
public class LocalDurableOrchestrator implements DurableOrchestrator {
    private final AgentRunRepository runs;
    private final DurableStores.RunEventStore events;
    private final DurableStores.CheckpointStore checkpoints;
    private final DurableStores.ApprovalService approvals;
    private final JdbcTemplate jdbc;

    public LocalDurableOrchestrator(AgentRunRepository runs, DurableStores.RunEventStore events,
                                    DurableStores.CheckpointStore checkpoints,
                                    DurableStores.ApprovalService approvals, JdbcTemplate jdbc) {
        this.runs = runs; this.events = events; this.checkpoints = checkpoints;
        this.approvals = approvals; this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public AgentRun create(String tenantId, String agentId, ExecutionMode executionMode,
                           String goal, int maxSteps,
                           BigDecimal maxCostUsd, Duration timeout) {
        if (maxSteps < 1 || maxSteps > 200) throw new IllegalArgumentException("invalid maxSteps");
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        Instant now = Instant.now();
        AgentRun run = new AgentRun(UUID.randomUUID().toString(), agentId, executionMode,
                goal, AgentRun.Status.PENDING, 0, maxSteps, maxCostUsd, now, now, tenantId,
                0, BigDecimal.ZERO, now.plus(timeout));
        runs.save(run);
        events.append(tenantId, run.getId(), null, "RUN_CREATED", "create:" + run.getId(), "{}");
        return run;
    }

    @Override
    @Transactional
    public AgentRun pause(String tenantId, String runId, String key) {
        if (replayed(tenantId, runId, key)) return required(tenantId, runId);
        return transition(tenantId, runId, key, "RUN_PAUSED", AgentRun.Status.PAUSED,
                AgentRun.Status.PENDING, AgentRun.Status.RUNNING);
    }

    @Override
    @Transactional
    public AgentRun resume(String tenantId, String runId, String key) {
        if (replayed(tenantId, runId, key)) return required(tenantId, runId);
        AgentRun run = required(tenantId, runId);
        if (run.getStatus() == AgentRun.Status.WAITING_APPROVAL) {
            Integer pending = jdbc.queryForObject("SELECT COUNT(*) FROM approval_request "
                    + "WHERE tenant_id=? AND run_id=? AND status='PENDING'", Integer.class,
                    tenantId, runId);
            Integer approved = jdbc.queryForObject("SELECT COUNT(*) FROM approval_request "
                    + "WHERE tenant_id=? AND run_id=? AND status='APPROVED'", Integer.class,
                    tenantId, runId);
            if (pending != 0 || approved == 0) {
                throw new IllegalStateException("run has no effective approval");
            }
        } else if (run.getStatus() != AgentRun.Status.PAUSED) {
            throw new IllegalStateException("run cannot resume from " + run.getStatus());
        }
        return update(run, AgentRun.Status.RUNNING, run.getCurrentStep(), run.getCostUsd(),
                tenantId, key, "RUN_RESUMED");
    }

    @Override
    @Transactional
    public AgentRun cancel(String tenantId, String runId, String key) {
        AgentRun run = required(tenantId, runId);
        if (terminal(run.getStatus())) return run;
        return update(run, AgentRun.Status.CANCELLED, run.getCurrentStep(), run.getCostUsd(),
                tenantId, key, "RUN_CANCELLED");
    }

    @Override
    @Transactional
    public ApprovalRequest awaitApproval(String tenantId, String runId, String stepId,
                                         String actionType, String parameters, Duration ttl,
                                         String key) {
        if (replayed(tenantId, runId, key)) {
            return approvals.findApprovals(tenantId, runId).stream()
                    .filter(a -> a.getStepId().equals(stepId)).reduce((first, second) -> second)
                    .orElseThrow(() -> new IllegalStateException("approval replay missing"));
        }
        AgentRun run = required(tenantId, runId);
        if (terminal(run.getStatus())) throw new IllegalStateException("run is terminal");
        update(run, AgentRun.Status.WAITING_APPROVAL, run.getCurrentStep(), run.getCostUsd(),
                tenantId, key, "RUN_WAITING_APPROVAL");
        return approvals.request(tenantId, runId, stepId, actionType, parameters, ttl);
    }

    @Override
    @Transactional
    public AgentRun recordStep(String tenantId, String runId, String stepId, String state,
                               BigDecimal stepCost, String key) {
        if (replayed(tenantId, runId, key)) return required(tenantId, runId);
        AgentRun run = required(tenantId, runId);
        if (run.getStatus() == AgentRun.Status.PAUSED
                || run.getStatus() == AgentRun.Status.WAITING_APPROVAL || terminal(run.getStatus())) {
            throw new IllegalStateException("run cannot execute from " + run.getStatus());
        }
        BigDecimal cost = run.getCostUsd().add(stepCost == null ? BigDecimal.ZERO : stepCost);
        AgentRun.Status target = AgentRun.Status.RUNNING;
        int nextStep = run.getCurrentStep() + 1;
        if (run.getTimeoutAt() != null && !Instant.now().isBefore(run.getTimeoutAt())) {
            target = AgentRun.Status.TIMED_OUT;
        } else if (run.getMaxCostUsd() != null && cost.compareTo(run.getMaxCostUsd()) > 0) {
            target = AgentRun.Status.BUDGET_EXCEEDED;
        } else if (nextStep >= run.getMaxSteps()) {
            target = AgentRun.Status.STEP_LIMIT_EXCEEDED;
        }
        AgentRun updated = update(run, target, Math.min(nextStep, run.getMaxSteps()), cost,
                tenantId, key, target == AgentRun.Status.RUNNING ? "STEP_COMPLETED" : target.name());
        checkpoints.save(tenantId, runId, stepId, state);
        return updated;
    }

    @Override
    @Transactional
    public AgentRun complete(String tenantId, String runId, String state, String key) {
        if (replayed(tenantId, runId, key)) return required(tenantId, runId);
        AgentRun run = required(tenantId, runId);
        if (run.getStatus() != AgentRun.Status.RUNNING
                && run.getStatus() != AgentRun.Status.PENDING
                && run.getStatus() != AgentRun.Status.VERIFYING) {
            throw new IllegalStateException("run cannot complete from " + run.getStatus());
        }
        checkpoints.save(tenantId, runId, "terminal", state == null ? "{}" : state);
        return update(run, AgentRun.Status.SUCCEEDED, run.getCurrentStep(), run.getCostUsd(),
                tenantId, key, "RUN_SUCCEEDED");
    }

    @Override
    @Transactional
    public AgentRun fail(String tenantId, String runId, String reason, String key) {
        if (replayed(tenantId, runId, key)) return required(tenantId, runId);
        AgentRun run = required(tenantId, runId);
        if (terminal(run.getStatus())) return run;
        checkpoints.save(tenantId, runId, "failure",
                "{\"reason\":\"" + jsonEscape(reason == null ? "failed" : reason) + "\"}");
        return update(run, AgentRun.Status.FAILED, run.getCurrentStep(), run.getCostUsd(),
                tenantId, key, "RUN_FAILED");
    }

    private AgentRun transition(String tenantId, String runId, String key, String event,
                                AgentRun.Status target, AgentRun.Status... allowed) {
        AgentRun run = required(tenantId, runId);
        boolean accepted = false;
        for (AgentRun.Status status : allowed) accepted |= run.getStatus() == status;
        if (!accepted) throw new IllegalStateException("invalid transition from " + run.getStatus());
        return update(run, target, run.getCurrentStep(), run.getCostUsd(), tenantId, key, event);
    }

    private AgentRun update(AgentRun run, AgentRun.Status status, int step, BigDecimal cost,
                            String tenantId, String key, String eventType) {
        boolean replay = events.findEvents(tenantId, run.getId()).stream()
                .anyMatch(e -> e.getIdempotencyKey().equals(key));
        if (replay) return required(tenantId, run.getId());
        Instant now = Instant.now();
        int changed = jdbc.update("UPDATE agent_run SET status=?,current_step=?,cost_usd=?,"
                        + "version=version+1,updated_at=? WHERE id=? AND tenant_id=? AND version=?",
                status.name(), step, cost, java.sql.Timestamp.from(now), run.getId(), tenantId,
                run.getVersion());
        if (changed != 1) throw new IllegalStateException("run version conflict");
        events.append(tenantId, run.getId(), null, eventType, key, "{}");
        return required(tenantId, run.getId());
    }

    private AgentRun required(String tenantId, String runId) {
        AgentRun run = runs.findById(runId)
                .orElseThrow(() -> new IllegalArgumentException("run not found: " + runId));
        if (!run.getTenantId().equals(tenantId)) throw new IllegalArgumentException("run not found");
        return run;
    }

    private boolean replayed(String tenantId, String runId, String key) {
        return events.findEvents(tenantId, runId).stream()
                .anyMatch(event -> event.getIdempotencyKey().equals(key));
    }

    private boolean terminal(AgentRun.Status status) {
        return status == AgentRun.Status.SUCCEEDED || status == AgentRun.Status.FAILED
                || status == AgentRun.Status.CANCELLED || status == AgentRun.Status.TIMED_OUT
                || status == AgentRun.Status.BUDGET_EXCEEDED
                || status == AgentRun.Status.STEP_LIMIT_EXCEEDED;
    }

    private static String jsonEscape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
