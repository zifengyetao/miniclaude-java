package com.miniclaude.infrastructure.durable;

import com.miniclaude.domain.durable.ApprovalRequest;
import com.miniclaude.domain.durable.DurableStores;
import com.miniclaude.domain.durable.RunCheckpoint;
import com.miniclaude.domain.durable.RunEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Durable 层 JDBC 持久化：事件日志、Checkpoint、审批三合一仓储。
 *
 * <p><b>实现端口</b>：{@link DurableStores.RunEventStore}、
 * {@link DurableStores.CheckpointStore}、{@link DurableStores.ApprovalService}。</p>
 *
 * <p><b>序列号设计</b>：{@link #nextSequence} 跨 {@code run_event}、
 * {@code run_checkpoint}、{@code approval_request} 三表取 MAX+1，使单一 {@code sequence_no}
 * 能还原「事件—快照—审批」的全局提交顺序。每次写入前 {@link #lockRun}（{@code FOR UPDATE}）
 * 串行化同 Run 的并发写。</p>
 *
 * <p><b>幂等与完整性</b>：
 * <ul>
 *   <li>事件：同 idempotency_key 只允许相同 type+payload_hash 重放</li>
 *   <li>审批：decide 时比对 action_hash，参数变更即拒绝；过期先物化 EXPIRED 再 fail</li>
 *   <li>{@link #sha256} 用于 payload/审批参数绑定，非加密签名</li>
 * </ul></p>
 */
@Repository
public class JdbcDurableStore implements DurableStores.RunEventStore,
        DurableStores.CheckpointStore, DurableStores.ApprovalService {
    private final JdbcTemplate jdbc;

    public JdbcDurableStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    @Transactional
    public RunEvent append(String tenantId, String runId, String stepId, String type,
                           String idempotencyKey, String payload) {
        require(tenantId, "tenantId"); require(runId, "runId"); require(type, "type");
        require(idempotencyKey, "idempotencyKey");
        // 对运行加行锁后再查重和分配序列，避免两个并发写入拿到同一“下一序号”。
        lockRun(tenantId, runId);
        String normalized = payload == null ? "{}" : payload;
        String hash = sha256(normalized);
        List<RunEvent> existing = jdbc.query(
                "SELECT * FROM run_event WHERE tenant_id=? AND run_id=? AND idempotency_key=?",
                this::event, tenantId, runId, idempotencyKey);
        if (!existing.isEmpty()) {
            RunEvent event = existing.get(0);
            // 幂等只允许完全相同的业务意图重放；同键异载荷可能掩盖调用方错误，必须拒绝。
            if (!event.getPayloadHash().equals(hash) || !event.getType().equals(type)) {
                throw new IllegalStateException("idempotency key reused with different event");
            }
            return event;
        }
        long sequence = nextSequence(tenantId, runId);
        RunEvent event = new RunEvent(UUID.randomUUID().toString(), tenantId, runId, stepId,
                sequence, type, idempotencyKey, normalized, hash, sequence, Instant.now());
        jdbc.update("INSERT INTO run_event(id,tenant_id,run_id,step_id,sequence_no,event_type,"
                            + "idempotency_key,payload,payload_hash,version,created_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    event.getId(), tenantId, runId, stepId, sequence, type, idempotencyKey,
                    normalized, hash, event.getVersion(), Timestamp.from(event.getCreatedAt()));
        return event;
    }

    @Override
    public List<RunEvent> findEvents(String tenantId, String runId) {
        return jdbc.query("SELECT * FROM run_event WHERE tenant_id=? AND run_id=? ORDER BY sequence_no",
                this::event, tenantId, runId);
    }

    @Override
    @Transactional
    public RunCheckpoint save(String tenantId, String runId, String stepId, String state) {
        require(stepId, "stepId");
        lockRun(tenantId, runId);
        String normalized = state == null ? "{}" : state;
        long sequence = nextSequence(tenantId, runId);
        // step 内版本支持同一步多次形成快照；不覆盖旧值才能保留完整恢复与审计轨迹。
        Long version = jdbc.queryForObject(
                "SELECT COALESCE(MAX(version),0)+1 FROM run_checkpoint "
                        + "WHERE tenant_id=? AND run_id=? AND step_id=?",
                Long.class, tenantId, runId, stepId);
        RunCheckpoint checkpoint = new RunCheckpoint(UUID.randomUUID().toString(), tenantId, runId,
                stepId, sequence, version, normalized, sha256(normalized), Instant.now());
        jdbc.update("INSERT INTO run_checkpoint(id,tenant_id,run_id,step_id,sequence_no,version,"
                        + "state_payload,state_hash,created_at) VALUES (?,?,?,?,?,?,?,?,?)",
                checkpoint.getId(), tenantId, runId, stepId, sequence, version, normalized,
                checkpoint.getStateHash(), Timestamp.from(checkpoint.getCreatedAt()));
        return checkpoint;
    }

    @Override
    public Optional<RunCheckpoint> latest(String tenantId, String runId) {
        List<RunCheckpoint> result = jdbc.query(
                "SELECT * FROM run_checkpoint WHERE tenant_id=? AND run_id=? "
                        + "ORDER BY sequence_no DESC LIMIT 1", this::checkpoint, tenantId, runId);
        return result.stream().findFirst();
    }

    @Override
    public List<RunCheckpoint> findCheckpoints(String tenantId, String runId) {
        return jdbc.query("SELECT * FROM run_checkpoint WHERE tenant_id=? AND run_id=? "
                + "ORDER BY sequence_no", this::checkpoint, tenantId, runId);
    }

    @Override
    @Transactional
    public ApprovalRequest request(String tenantId, String runId, String stepId, String actionType,
                                   String actionParameters, Duration ttl) {
        require(actionType, "actionType"); require(actionParameters, "actionParameters");
        lockRun(tenantId, runId);
        // 非正 TTL 会产生无法安全解释的授权窗口，因此在写入前直接拒绝。
        if (ttl == null || ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("approval ttl must be positive");
        }
        Instant now = Instant.now();
        long sequence = nextSequence(tenantId, runId);
        // 只持久化参数摘要作为审批绑定证据；决定时重算并比较，参数被替换即不授权。
        ApprovalRequest request = new ApprovalRequest(UUID.randomUUID().toString(), tenantId, runId,
                stepId, sequence, 1, actionType, actionParameters, sha256(actionParameters),
                ApprovalRequest.Status.PENDING, now, now.plus(ttl), null, null, null);
        jdbc.update("INSERT INTO approval_request(id,tenant_id,run_id,step_id,sequence_no,version,"
                        + "action_type,action_parameters,action_hash,status,requested_at,expires_at) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                request.getId(), tenantId, runId, stepId, sequence, 1, actionType, actionParameters,
                request.getActionHash(), request.getStatus().name(), Timestamp.from(now),
                Timestamp.from(request.getExpiresAt()));
        append(tenantId, runId, stepId, "APPROVAL_REQUESTED", "approval:" + request.getId(),
                "{\"approvalId\":\"" + request.getId() + "\"}");
        return request;
    }

    @Override
    @Transactional
    public ApprovalRequest decide(String tenantId, String approvalId, String expectedParameters,
                                  ApprovalRequest.Status decision, String actor, String reason) {
        if (decision != ApprovalRequest.Status.APPROVED
                && decision != ApprovalRequest.Status.REJECTED) {
            throw new IllegalArgumentException("decision must be APPROVED or REJECTED");
        }
        ApprovalRequest current = find(tenantId, approvalId)
                .orElseThrow(() -> new IllegalArgumentException("approval not found"));
        if (current.getStatus() != ApprovalRequest.Status.PENDING) {
            throw new IllegalStateException("approval is not pending: " + current.getStatus());
        }
        // 审批对象是“这一组参数”而非抽象动作类型；任何字节级变化都按未获批准处理。
        if (!current.getActionHash().equals(sha256(expectedParameters == null ? "" : expectedParameters))) {
            throw new IllegalStateException("approval action parameters changed");
        }
        Instant now = Instant.now();
        if (!now.isBefore(current.getExpiresAt())) {
            // 先将过期事实落库再拒绝，后续读取不会继续把该请求误显示为可决定。
            jdbc.update("UPDATE approval_request SET status='EXPIRED',version=version+1,"
                    + "decided_at=? WHERE id=? AND status='PENDING'", Timestamp.from(now), approvalId);
            throw new IllegalStateException("approval expired");
        }
        int changed = jdbc.update("UPDATE approval_request SET status=?,version=version+1,"
                        + "decided_at=?,decided_by=?,decision_reason=? "
                        + "WHERE id=? AND tenant_id=? AND status='PENDING' AND expires_at>?",
                decision.name(), Timestamp.from(now), actor, reason, approvalId, tenantId,
                Timestamp.from(now));
        // 条件更新保证审批只能被决定一次；并发落败者不能覆盖先到决定。
        if (changed != 1) throw new IllegalStateException("approval decision lost race");
        append(tenantId, current.getRunId(), current.getStepId(), "APPROVAL_" + decision.name(),
                "approval-decision:" + approvalId, "{\"approvalId\":\"" + approvalId + "\"}");
        return find(tenantId, approvalId).orElseThrow(IllegalStateException::new);
    }

    @Override
    @Transactional
    public Optional<ApprovalRequest> find(String tenantId, String approvalId) {
        expirePending(tenantId);
        List<ApprovalRequest> result = jdbc.query(
                "SELECT * FROM approval_request WHERE tenant_id=? AND id=?",
                this::approval, tenantId, approvalId);
        return result.stream().findFirst();
    }

    @Override
    @Transactional
    public List<ApprovalRequest> findApprovals(String tenantId, String runId) {
        expirePending(tenantId);
        return jdbc.query("SELECT * FROM approval_request WHERE tenant_id=? AND run_id=? "
                + "ORDER BY sequence_no", this::approval, tenantId, runId);
    }

    private void expirePending(String tenantId) {
        // 读取前惰性物化过期状态，使“未运行后台清理任务”不会扩大授权有效期。
        jdbc.update("UPDATE approval_request SET status='EXPIRED',version=version+1,decided_at=? "
                        + "WHERE tenant_id=? AND status='PENDING' AND expires_at<=?",
                Timestamp.from(Instant.now()), tenantId, Timestamp.from(Instant.now()));
    }

    /**
     * 计算跨 event/checkpoint/approval 的下一个全局 sequence_no。
     *
     * <p>调用方须已 {@link #lockRun}，否则并发可能分配重复序号。</p>
     */
    private long nextSequence(String tenantId, String runId) {
        // 序列跨三张表计算，才能按一个数字还原“事件—快照—审批”的真实提交顺序。
        // 调用者已经持有运行行锁，因此 MAX+1 在同一运行内不会并发冲突。
        Long value = jdbc.queryForObject(
                "SELECT COALESCE(MAX(sequence_no),0)+1 FROM ("
                        + "SELECT sequence_no FROM run_event WHERE tenant_id=? AND run_id=? "
                        + "UNION ALL SELECT sequence_no FROM run_checkpoint WHERE tenant_id=? AND run_id=? "
                        + "UNION ALL SELECT sequence_no FROM approval_request WHERE tenant_id=? AND run_id=?"
                        + ") durable_sequence",
                Long.class, tenantId, runId, tenantId, runId, tenantId, runId);
        return value == null ? 1 : value;
    }

    /**
     * 对 {@code agent_run} 行加 {@code FOR UPDATE} 锁，并校验 tenant+run 存在。
     *
     * <p>所有 append/save/request/decide 的序列化锚点。</p>
     */
    private void lockRun(String tenantId, String runId) {
        // 运行行既是存在性/租户校验，也是所有持久子记录共享的序列化锁。
        List<String> ids = jdbc.query("SELECT id FROM agent_run WHERE tenant_id=? AND id=? FOR UPDATE",
                (rs, row) -> rs.getString(1), tenantId, runId);
        if (ids.isEmpty()) throw new IllegalArgumentException("run not found");
    }

    /** ResultSet → {@link RunEvent} */
    private RunEvent event(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new RunEvent(rs.getString("id"), rs.getString("tenant_id"), rs.getString("run_id"),
                rs.getString("step_id"), rs.getLong("sequence_no"), rs.getString("event_type"),
                rs.getString("idempotency_key"), rs.getString("payload"),
                rs.getString("payload_hash"), rs.getLong("version"),
                rs.getTimestamp("created_at").toInstant());
    }

    /** ResultSet → {@link RunCheckpoint} */
    private RunCheckpoint checkpoint(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        return new RunCheckpoint(rs.getString("id"), rs.getString("tenant_id"), rs.getString("run_id"),
                rs.getString("step_id"), rs.getLong("sequence_no"), rs.getLong("version"),
                rs.getString("state_payload"), rs.getString("state_hash"),
                rs.getTimestamp("created_at").toInstant());
    }

    /** ResultSet → {@link ApprovalRequest} */
    private ApprovalRequest approval(java.sql.ResultSet rs, int row) throws java.sql.SQLException {
        Timestamp decided = rs.getTimestamp("decided_at");
        return new ApprovalRequest(rs.getString("id"), rs.getString("tenant_id"), rs.getString("run_id"),
                rs.getString("step_id"), rs.getLong("sequence_no"), rs.getLong("version"),
                rs.getString("action_type"), rs.getString("action_parameters"),
                rs.getString("action_hash"), ApprovalRequest.Status.valueOf(rs.getString("status")),
                rs.getTimestamp("requested_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                decided == null ? null : decided.toInstant(), rs.getString("decided_by"),
                rs.getString("decision_reason"));
    }

    /**
     * 计算稳定的十六进制 SHA-256 摘要，用于检测持久载荷或审批参数变化。
     *
     * <p>摘要用于完整性绑定，不替代签名、加密或身份认证。</p>
     */
    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : digest) hex.append(String.format("%02x", b));
            return hex.toString();
        } catch (Exception impossible) {
            // 运行环境缺少标准算法时不能降级为弱摘要，否则审批绑定与幂等比较失去保证。
            throw new IllegalStateException("SHA-256 unavailable", impossible);
        }
    }

    /** 非空非空白字符串校验，用于写入前 fail-fast */
    private static void require(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " required");
    }
}
