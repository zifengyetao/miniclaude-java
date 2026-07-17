package com.miniclaude.application.governance;

import com.miniclaude.domain.governance.VersionedAsset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AntiRotService {
    private final JdbcTemplate jdbc;
    private final RegistryService registry;
    private final AuditService audit;
    private final int staleDays;
    private final int promptMaxChars;

    public AntiRotService(JdbcTemplate jdbc, RegistryService registry, AuditService audit,
                          @Value("${governed.evolution.anti-rot.stale-days:180}") int staleDays,
                          @Value("${governed.evolution.anti-rot.prompt-max-chars:12000}") int promptMaxChars) {
        this.jdbc = jdbc;
        this.registry = registry;
        this.audit = audit;
        this.staleDays = staleDays;
        this.promptMaxChars = promptMaxChars;
    }

    public List<Map<String, Object>> scan(String tenant, String currentModel, String actor) {
        List<VersionedAsset> assets = registry.list(tenant);
        Map<String, List<VersionedAsset>> hashes = new HashMap<>();
        for (VersionedAsset asset : assets) {
            if (asset.getStatus() != VersionedAsset.Status.PUBLISHED) continue;
            hashes.computeIfAbsent(asset.getContentHash(), ignored -> new ArrayList<>()).add(asset);
            if (Duration.between(asset.getCreatedAt(), Instant.now()).toDays() > staleDays) {
                finding(asset, "STALE", "MEDIUM", "asset age exceeds " + staleDays + " days",
                        "review evidence and supersede through a governed candidate");
            }
            if (asset.getContent().contains("CONFLICT:")) {
                finding(asset, "CONFLICT", "HIGH", "explicit conflict marker found",
                        "resolve precedence through review; do not delete the asset directly");
            }
            if (asset.getType() == VersionedAsset.Type.PROMPT
                    && asset.getContent().length() > promptMaxChars) {
                finding(asset, "PROMPT_BLOAT", "MEDIUM",
                        "prompt length " + asset.getContent().length() + " exceeds " + promptMaxChars,
                        "distill repeated guidance into a reviewed successor");
            }
            if (currentModel != null && !currentModel.trim().isEmpty()
                    && asset.getContent().contains("[models:")
                    && !asset.getContent().contains(currentModel)) {
                finding(asset, "MODEL_INCOMPATIBLE", "HIGH",
                        "compatibility marker does not include " + currentModel,
                        "evaluate a compatible successor before changing production");
            }
        }
        for (List<VersionedAsset> duplicates : hashes.values()) {
            if (duplicates.size() > 1) {
                for (VersionedAsset duplicate : duplicates) {
                    finding(duplicate, "DUPLICATE", "LOW",
                            "content hash is shared by " + duplicates.size() + " published assets",
                            "consolidate references through a reviewed candidate");
                }
            }
        }
        audit.append(tenant, "USER", actor, "ANTI_ROT_SCANNED", "ANTI_ROT", tenant,
                "COMPLETED", "assets=" + assets.size(), null, null);
        return findings(tenant);
    }

    public List<Map<String, Object>> findings(String tenant) {
        return jdbc.queryForList("SELECT * FROM anti_rot_finding WHERE tenant_id=?"
                + " ORDER BY detected_at DESC", tenant);
    }

    private void finding(VersionedAsset asset, String type, String severity,
                         String evidence, String recommendation) {
        Integer open = jdbc.queryForObject("SELECT COUNT(*) FROM anti_rot_finding"
                        + " WHERE asset_id=? AND finding_type=? AND status='OPEN'",
                Integer.class, asset.getId(), type);
        if (open != null && open > 0) return;
        jdbc.update("INSERT INTO anti_rot_finding (id,tenant_id,asset_id,finding_type,severity,status,"
                        + "evidence,recommendation,detected_at) VALUES (?,?,?,?,?,?,?,?,?)",
                UUID.randomUUID().toString(), asset.getTenantId(), asset.getId(), type, severity,
                "OPEN", evidence, recommendation, Timestamp.from(Instant.now()));
    }
}
