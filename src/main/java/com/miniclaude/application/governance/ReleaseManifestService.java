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

/**
 * 构建并验证可复现发布清单。
 *
 * <p>创建时把外部 {@code key@version} 转为 {@code key@version#contentHash} 精确 pin，
 * 并按资产类型排序后计算清单 hash。发布前重新验证清单和每个资产，任何坐标缺失、资产非
 * PUBLISHED、内容变化或清单摘要不一致都会阻断发布。</p>
 */
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
        // TreeMap 提供稳定序列化顺序；否则相同 pins 可能因 Map 遍历顺序不同产生不同清单 hash。
        TreeMap<String, String> canonicalPins = new TreeMap<>();
        for (Map.Entry<String, String> pin : pins.entrySet()) {
            VersionedAsset.Type type = VersionedAsset.Type.valueOf(pin.getKey().toUpperCase());
            String[] coordinate = pin.getValue().split("@", -1);
            // 范围版本、latest 或缺失版本不能形成可复现发布，必须在清单创建阶段拒绝。
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

    /**
     * 将 DRAFT 清单发布为 RELEASED。
     *
     * @param expectedHash 乐观锁：须与 verify 后 manifestHash 一致
     */
    @Transactional
    public AgentReleaseManifest release(String id, String expectedHash, String actor) {
        // expectedHash 是乐观确认令牌：审批者确认的是当前清单，而不是同 ID 下可能变化的内容。
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

    /**
     * 验证清单 hash 与各 pin 资产内容 hash。
     *
     * @throws IllegalStateException hash 或 pin 不一致
     */
    public AgentReleaseManifest verify(String id) {
        AgentReleaseManifest manifest = get(id);
        // 先验证清单整体，再逐项验证 pin；两层校验分别覆盖清单编排篡改与资产正文篡改。
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

    /** @throws IllegalArgumentException 清单不存在 */
    public AgentReleaseManifest get(String id) {
        List<AgentReleaseManifest> rows = jdbc.query("SELECT * FROM agent_release_manifest WHERE id=?", mapper, id);
        if (rows.isEmpty()) throw new IllegalArgumentException("manifest not found");
        return rows.get(0);
    }
}
