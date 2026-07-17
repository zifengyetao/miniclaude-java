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

    public VersionedAsset resolve(String tenant, VersionedAsset.Type type, String key,
                                  String version, boolean forRun) {
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

    public List<VersionedAsset> list(String tenant) {
        return jdbc.query("SELECT * FROM versioned_asset WHERE tenant_id=? ORDER BY created_at DESC",
                mapper, tenant);
    }

    public VersionedAsset getById(String id) {
        List<VersionedAsset> rows = jdbc.query("SELECT * FROM versioned_asset WHERE id=?", mapper, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("asset not found");
        return rows.get(0);
    }

    private static void requireExactVersion(String version) {
        require(version, "version");
        if ("latest".equalsIgnoreCase(version.trim()) || version.contains("*")) {
            throw new IllegalArgumentException("exact asset version is required; latest/wildcards are forbidden");
        }
    }

    private static void require(String value, String name) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(name + " is required");
    }
}
