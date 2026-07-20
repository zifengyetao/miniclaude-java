package com.miniclaude.application.scenario;

import com.miniclaude.domain.runtime.HarnessProfile;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;

/** Data/Support/Coding 共用 Loop 的版本化 Profile 目录。 */
@Service
public class HarnessProfileCatalog {
    private final Map<String, HarnessProfile> profiles = new LinkedHashMap<>();

    public HarnessProfileCatalog() {
        register(new HarnessProfile(
                ScenarioCatalog.ANALYST, "1.0.0",
                "你是受控数据分析 Agent。只使用指标检索和只读查询工具；SQL 必须经过 Guard，"
                        + "高成本查询必须暂停审批；最终报告必须包含引用，禁止任何数据库写入。",
                tools("metric_lookup", "sql_guard", "estimate_query", "execute_read_only",
                        "verify_citations", "emit_report_draft"),
                HarnessProfile.AutonomyMode.READ_ONLY_AUTO, 12, 20, 24000));
        register(new HarnessProfile(
                ScenarioCatalog.SUPPORT, "1.0.0",
                "你是建议型客服 Agent。原始 PII 必须先脱敏；只能生成未发送回复草稿。"
                        + "敏感投诉、法律或低置信问题必须请求人工转交，禁止调用 CRM 发送能力。",
                tools("mask_pii", "knowledge_search", "check_compliance",
                        "draft_reply", "request_human_handoff"),
                HarnessProfile.AutonomyMode.CONTROLLED, 10, 16, 20000));
        register(new HarnessProfile(
                ScenarioCatalog.CODING, "1.0.0",
                "你是隔离工作区内的 Coding Agent。先探索和计划，再提出 Patch、运行测试并独立复核。"
                        + "禁止写 main/master、force push、--no-verify、生产部署和读取密钥；默认只产出 PR 草稿。",
                tools("repo_search", "read_file", "propose_patch", "run_build",
                        "run_tests", "review_patch", "emit_pr_draft"),
                HarnessProfile.AutonomyMode.CONTROLLED, 30, 80, 48000));
    }

    public HarnessProfile get(String id) {
        HarnessProfile profile = profiles.get(id);
        if (profile == null) throw new IllegalArgumentException("harness profile not found: " + id);
        return profile;
    }

    public Map<String, HarnessProfile> list() {
        return Collections.unmodifiableMap(profiles);
    }

    private void register(HarnessProfile profile) {
        profiles.put(profile.getId(), profile);
    }

    private static LinkedHashSet<String> tools(String... names) {
        return new LinkedHashSet<>(Arrays.asList(names));
    }
}
