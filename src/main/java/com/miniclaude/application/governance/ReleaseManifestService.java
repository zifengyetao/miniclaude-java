package com.miniclaude.application.governance;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import com.miniclaude.domain.governance.AgentReleaseManifest;
import com.miniclaude.domain.governance.GovernanceHash;
import com.miniclaude.domain.governance.VersionedAsset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.lang.reflect.Type;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

@Service
public class ReleaseManifestService {
    private static final Type MAP_TYPE = new TypeToken<Map<String, String>>() { }.getType();
    private final JdbcTemplate jdbc;
    private final RegistryService registry;
    private final AuditService audit;
    private final Gson gson = new Gson();
    private final RowMapper<AgentReleaseManifest> mapper = (rs, n) -> new AgentReleaseManifest(
            rs.getString("id"), rs.getString("tenant_id"), rs.getString("agent_key"),
            rs.getString("version"), AgentReleaseManifest.Status.valueOf(rs.getString("status")),
            gson.fromJson(rs.getString("assets_json"), MAP_TYPE), rs.getString("manifest_hash"),
            rs.getString("signature"), rs.getString("created_by"), rs.getTimestamp("created_at").toInstant());

    public ReleaseManifestService(JdbcTemplate jdbc, RegistryService registry, AuditService audit) {
        this.jdbc = jdbc; this.registry = registry; this.audit = audit;
    }

    @Transactional
    public AgentReleaseManifest create(String tenant, String agentKey, String version,
                                       Map<String, String> pins, String signature, String actor) {
        if (pins == null || pins.isEmpty()) throw new IllegalArgumentException("asset pins are required");
        TreeMap<String, String> canonicalPins = new TreeMap<>();
        for (Map.Entry<String, String> pin : pins.entrySet()) {
            VersionedAsset.Type type = VersionedAsset.Type.valueOf(pin.getKey().toUpperCase());
            String[] coordinate = pin.getValue().split("@", -1);
            if (coordinate.length != 2) throw new IllegalArgumentException("pin must be key@exactVersion");
            VersionedAsset asset = registry.resolve(tenant, type, coordinate[0], coordinate[1], true);
            canonicalPins.put(type.name(), asset.getKey() + "@" + asset.getVersion() + "#" + asset.getContentHash());
        }
        String json = gson.toJson(canonicalPins);
        String hash = GovernanceHash.sha256(tenant + "|" + agentKey + "|" + version + "|" + json);
        String id = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO agent_release_manifest (id,tenant_id,agent_key,version,status,assets_json,"
                        + "manifest_hash,signature,created_by,created_at) VALUES (?,?,?,?,?,?,?,?,?,?)",
                id, tenant, agentKey, version, AgentReleaseManifest.Status.DRAFT.name(), json,
                hash, signature, actor, Timestamp.from(Instant.now()));
        audit.append(tenant, "USER", actor, "MANIFEST_CREATED", "RELEASE", id, "DRAFT", hash, null, null);
        return get(id);
    }

    @Transactional
    public AgentReleaseManifest release(String id, String expectedHash, String actor) {
        AgentReleaseManifest manifest = verify(id);
        if (manifest.getStatus() != AgentReleaseManifest.Status.DRAFT
                || !manifest.getManifestHash().equals(expectedHash)) {
            throw new IllegalArgumentException("manifest status/hash verification failed");
        }
        jdbc.update("UPDATE agent_release_manifest SET status=?, released_at=? WHERE id=? AND status=?",
                AgentReleaseManifest.Status.RELEASED.name(), Timestamp.from(Instant.now()), id,
                AgentReleaseManifest.Status.DRAFT.name());
        audit.append(manifest.getTenantId(), "USER", actor, "MANIFEST_RELEASED", "RELEASE", id,
                "RELEASED", expectedHash, null, null);
        return get(id);
    }

    public AgentReleaseManifest verify(String id) {
        AgentReleaseManifest manifest = get(id);
        String json = gson.toJson(new TreeMap<>(manifest.getAssetPins()));
        String expected = GovernanceHash.sha256(manifest.getTenantId() + "|" + manifest.getAgentKey()
                + "|" + manifest.getVersion() + "|" + json);
        if (!expected.equals(manifest.getManifestHash())) throw new IllegalStateException("manifest hash mismatch");
        for (Map.Entry<String, String> pin : manifest.getAssetPins().entrySet()) {
            String[] hashSplit = pin.getValue().split("#", -1);
            String[] coordinate = hashSplit[0].split("@", -1);
            VersionedAsset asset = registry.resolve(manifest.getTenantId(),
                    VersionedAsset.Type.valueOf(pin.getKey()), coordinate[0], coordinate[1], true);
            if (hashSplit.length != 2 || !asset.getContentHash().equals(hashSplit[1])) {
                throw new IllegalStateException("manifest pinned asset hash mismatch");
            }
        }
        return manifest;
    }

    public AgentReleaseManifest get(String id) {
        List<AgentReleaseManifest> rows = jdbc.query("SELECT * FROM agent_release_manifest WHERE id=?", mapper, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("manifest not found");
        return rows.get(0);
    }
}
