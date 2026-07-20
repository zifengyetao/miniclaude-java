package com.miniclaude.application.governance;

import com.miniclaude.domain.governance.GovernanceHash;
import com.miniclaude.domain.governance.VersionedAsset;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * 版本化资产注册表。
 *
 * <p>“不可变”由追加新版本、唯一坐标和状态迁移共同实现：已发布记录没有内容更新入口；
 * 修订必须创建带 parentId 的后继草稿。解析仅接受精确版本且只返回 PUBLISHED，
 * 因而 latest、通配符、已撤销版本和内容 hash 不一致都会失败关闭。</p>
 */
@Service
public class RegistryService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final MeterRegistry meters;
    private final RowMapper<VersionedAsset> mapper = (rs, n) -> new VersionedAsset(
            rs.getString("id"), rs.getString("tenant_id"),
            VersionedAsset.Type.valueOf(rs.getString("asset_type")), rs.getString("asset_key"),
            rs.getString("version"), rs.getString("parent_id"),
            VersionedAsset.Status.valueOf(rs.getString("status")), rs.getString("content"),
            rs.getString("content_hash"), rs.getString("signature"), rs.getString("created_by"),
            rs.getTimestamp("created_at").toInstant());

    public RegistryService(JdbcTemplate jdbc, AuditService audit, MeterRegistry meters) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.meters = meters;
    }

    @Transactional
    public VersionedAsset createDraft(String tenant, VersionedAsset.Type type, String key, String version,
                                      String parentId, String content, String signature, String actor) {
        requireExactVersion(version);
        require(content, "content");
        // 父资产必须真实存在；后继关系保留版本谱系，不能用一个悬空引用伪造来源。
        if (parentId != null) getById(parentId);
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String hash = GovernanceHash.sha256(content);
        jdbc.update("INSERT INTO versioned_asset (id,tenant_id,asset_type,asset_key,version,parent_id,status,"
                        + "content,content_hash,signature,created_by,created_at) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                id, tenant, type.name(), key, version, parentId, VersionedAsset.Status.DRAFT.name(),
                content, hash, signature, actor, Timestamp.from(now));
        audit.append(tenant, "USER", actor, "ASSET_DRAFTED", "ASSET", id, "DRAFT", hash, null, null);
        meters.counter("agentops.registry.assets", "type", type.name(), "operation", "draft").increment();
        return getById(id);
    }

    @Transactional
    public VersionedAsset publish(String id, String expectedHash, String actor) {
        VersionedAsset asset = getById(id);
        if (asset.getStatus() != VersionedAsset.Status.DRAFT) {
            throw new IllegalStateException("only a draft asset can be published");
        }
        // 同时比较调用方期望值、入库摘要与当前内容复算值，防止陈旧审批或存储层篡改被发布。
        if (!asset.getContentHash().equals(expectedHash)
                || !GovernanceHash.sha256(asset.getContent()).equals(expectedHash)) {
            throw new IllegalArgumentException("asset hash verification failed");
        }
        jdbc.update("UPDATE versioned_asset SET status=?, published_at=? WHERE id=? AND status=?",
                VersionedAsset.Status.PUBLISHED.name(), Timestamp.from(Instant.now()), id,
                VersionedAsset.Status.DRAFT.name());
        audit.append(asset.getTenantId(), "USER", actor, "ASSET_PUBLISHED", "ASSET", id,
                "PUBLISHED", expectedHash, null, null);
        return getById(id);
    }

    @Transactional
    public VersionedAsset deprecate(String id, String actor) {
        VersionedAsset asset = getById(id);
        if (asset.getStatus() != VersionedAsset.Status.PUBLISHED) {
            throw new IllegalStateException("only a published asset can be deprecated");
        }
        jdbc.update("UPDATE versioned_asset SET status=?, deprecated_at=? WHERE id=? AND status=?",
                VersionedAsset.Status.DEPRECATED.name(), Timestamp.from(Instant.now()), id,
                VersionedAsset.Status.PUBLISHED.name());
        audit.append(asset.getTenantId(), "USER", actor, "ASSET_DEPRECATED", "ASSET", id,
                "DEPRECATED", asset.getContentHash(), null, null);
        return getById(id);
    }

    @Transactional
    public VersionedAsset revoke(String id, String actor, String reason) {
        VersionedAsset asset = getById(id);
        if (asset.getStatus() != VersionedAsset.Status.PUBLISHED) {
            throw new IllegalStateException("only a published asset can be revoked");
        }
        jdbc.update("UPDATE versioned_asset SET status=?, deprecated_at=? WHERE id=? AND status=?",
                VersionedAsset.Status.REVOKED.name(), Timestamp.from(Instant.now()), id,
                VersionedAsset.Status.PUBLISHED.name());
        audit.append(asset.getTenantId(), "USER", actor, "ASSET_REVOKED", "ASSET", id,
                "REVOKED", reason, null, null);
        return getById(id);
    }

    /**
     * 解析精确版本的 PUBLISHED 资产并复算 content hash。
     *
     * @param forRun 运行期解析标志；仍禁止 latest/通配符
     */
    public VersionedAsset resolve(String tenant, VersionedAsset.Type type, String key,
                                  String version, boolean forRun) {
        // 运行期也禁止“latest”；否则同一发布清单在不同时间可能解析到不同内容，无法复现与回滚。
        requireExactVersion(version);
        List<VersionedAsset> rows = jdbc.query("SELECT * FROM versioned_asset WHERE tenant_id=? "
                        + "AND asset_type=? AND asset_key=? AND version=? AND status=?",
                mapper, tenant, type.name(), key, version, VersionedAsset.Status.PUBLISHED.name());
        if (rows.isEmpty()) throw new IllegalArgumentException("published asset version not found");
        VersionedAsset asset = rows.get(0);
        if (!GovernanceHash.sha256(asset.getContent()).equals(asset.getContentHash())) {
            throw new IllegalStateException("stored asset hash mismatch");
        }
        return asset;
    }

    /** @return 租户全部资产（各状态），按创建时间降序 */
    public List<VersionedAsset> list(String tenant) {
        return jdbc.query("SELECT * FROM versioned_asset WHERE tenant_id=? ORDER BY created_at DESC",
                mapper, tenant);
    }

    /** @throws IllegalArgumentException 资产不存在 */
    public VersionedAsset getById(String id) {
        List<VersionedAsset> rows = jdbc.query("SELECT * FROM versioned_asset WHERE id=?", mapper, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("asset not found");
        return rows.get(0);
    }

    private static void requireExactVersion(String version) {
        require(version, "version");
        // 当前约束明确拦截两类漂移表达式；数据库唯一键再保证同一精确坐标不能被覆盖。
        if ("latest".equalsIgnoreCase(version.trim()) || version.contains("*")) {
            throw new IllegalArgumentException("exact asset version is required; latest/wildcards are forbidden");
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
    }
}
