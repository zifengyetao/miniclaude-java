package com.miniclaude.application.governance;

import com.miniclaude.domain.governance.GovernanceHash;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** 追加式、哈希串联的审计日志；不提供更新或删除能力。 */
@Service
public class AuditService {
    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Map<String, Object> append(String tenantId, String actorType, String actorId,
                                      String operation, String resourceType, String resourceId,
                                      String decision, String payload, String traceId, String runId) {
        require(tenantId, "tenantId");
        require(actorId, "actorId");
        String previous = jdbc.query(
                "SELECT event_hash FROM audit_event WHERE tenant_id=? ORDER BY occurred_at DESC, id DESC",
                rs -> rs.next() ? rs.getString(1) : null, tenantId);
        String id = UUID.randomUUID().toString();
        Instant occurredAt = Instant.now();
        String payloadHash = GovernanceHash.sha256(payload == null ? "" : payload);
        String eventHash = GovernanceHash.sha256(String.join("|", tenantId, id, occurredAt.toString(),
                actorType, actorId, operation, resourceType, resourceId,
                decision == null ? "" : decision, payloadHash, previous == null ? "" : previous,
                traceId == null ? "" : traceId, runId == null ? "" : runId));
        jdbc.update("INSERT INTO audit_event (id,tenant_id,occurred_at,actor_type,actor_id,operation,"
                        + "resource_type,resource_id,decision,payload_hash,previous_hash,event_hash,trace_id,run_id)"
                        + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                id, tenantId, Timestamp.from(occurredAt), actorType, actorId, operation,
                resourceType, resourceId, decision, payloadHash, previous, eventHash, traceId, runId);
        return get(id);
    }

    public List<Map<String, Object>> query(String tenantId, String resourceType, String resourceId) {
        StringBuilder sql = new StringBuilder("SELECT * FROM audit_event WHERE tenant_id=?");
        java.util.ArrayList<Object> args = new java.util.ArrayList<>();
        args.add(tenantId);
        if (resourceType != null && !resourceType.trim().isEmpty()) {
            sql.append(" AND resource_type=?");
            args.add(resourceType);
        }
        if (resourceId != null && !resourceId.trim().isEmpty()) {
            sql.append(" AND resource_id=?");
            args.add(resourceId);
        }
        sql.append(" ORDER BY occurred_at DESC");
        return jdbc.queryForList(sql.toString(), args.toArray());
    }

    private Map<String, Object> get(String id) {
        return jdbc.queryForMap("SELECT * FROM audit_event WHERE id=?", id);
    }

    private static void require(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
    }
}
