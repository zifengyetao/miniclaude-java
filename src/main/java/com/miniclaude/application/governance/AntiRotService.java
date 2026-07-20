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

/**
 * 已发布资产的 anti-rot（防腐化）扫描器。
 *
 * <p>扫描只产生可审阅 finding，不删除、不改写、不自动合并资产。陈旧、冲突、提示膨胀、模型
 * 不兼容和重复内容只是风险证据；真正修复必须走“后继候选→评测→复核→灰度→晋升”链路。
 * 这避免启发式规则误判时直接破坏生产基线或审计历史。</p>
 */
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

    /**
     * 扫描租户已发布资产的腐化风险并写入 finding（不修改资产）。
     *
     * @param currentModel 可选；用于 MODEL_INCOMPATIBLE 检测
     * @return 扫描后租户全部 finding（含历史 OPEN）
     */
    public List<Map<String, Object>> scan(String tenant, String currentModel, String actor) {
        List<VersionedAsset> assets = registry.list(tenant);
        Map<String, List<VersionedAsset>> hashes = new HashMap<>();
        for (VersionedAsset asset : assets) {
            // 草稿、弃用和撤销版本不代表当前稳定基线，避免为历史版本制造噪声 finding。
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

    /** @return 租户 anti-rot finding 列表，按检测时间降序 */
    public List<Map<String, Object>> findings(String tenant) {
        return jdbc.queryForList("SELECT * FROM anti_rot_finding WHERE tenant_id=?"
                + " ORDER BY detected_at DESC", tenant);
    }

    private void finding(VersionedAsset asset, String type, String severity,
                         String evidence, String recommendation) {
        // 同一资产同类 OPEN finding 去重；重复扫描应积累证据而不是制造告警风暴。
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
