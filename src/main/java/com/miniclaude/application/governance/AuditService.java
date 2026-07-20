package com.miniclaude.application.governance;

import com.miniclaude.domain.governance.GovernanceHash;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 追加式、按租户哈希串联的审计日志；不提供更新或删除能力。
 *
 * <p>每个事件同时保存 payload 摘要、前一事件摘要和自身摘要。修改任一历史事件都会使后续链条
 * 无法复算一致；首事件的 previousHash 为空。该机制用于暴露篡改，不替代数据库访问控制、
 * 外部锚定或签名：能重写整条链的高权限攻击者仍需由独立备份/锚点检测。</p>
 */
@Service
public class AuditService {
    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * 追加一条审计事件到租户 hash 链。
     *
     * @return 新插入的审计行 Map
     * @implNote 副作用：INSERT；无 update/delete API
     */
    public Map<String, Object> append(String tenantId, String actorType, String actorId,
                                      String operation, String resourceType, String resourceId,
                                      String decision, String payload, String traceId, String runId) {
        require(tenantId, "tenantId");
        require(actorId, "actorId");
        // 只连接同租户的最新事件，避免跨租户信息泄漏，也保证每个租户可独立验证自己的链。
        String previous = jdbc.query(
                "SELECT event_hash FROM audit_event WHERE tenant_id=? ORDER BY occurred_at DESC, id DESC",
                rs -> rs.next() ? rs.getString(1) : null, tenantId);
        String id = UUID.randomUUID().toString();
        Instant occurredAt = Instant.now();
        // 审计表不落原始正文，只记录摘要；敏感 payload 泄漏面因此受限，但调用方仍应避免传入秘密。
        String payloadHash = GovernanceHash.sha256(payload == null ? "" : payload);
        // 字段顺序是 hash 协议的一部分；任意改序都会造成历史事件校验失败。
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

    /**
     * 按租户及可选资源过滤查询审计事件（降序）。
     *
     * @param resourceType 可 null，非空时过滤
     * @param resourceId   可 null，非空时过滤
     */
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
