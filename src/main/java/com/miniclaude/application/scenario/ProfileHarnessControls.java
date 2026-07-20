package com.miniclaude.application.scenario;

import com.miniclaude.domain.runtime.HarnessCompletionVerifier;
import com.miniclaude.domain.runtime.HarnessModelGateway;
import com.miniclaude.domain.runtime.HarnessProfile;
import com.miniclaude.domain.runtime.HarnessToolGuard;
import com.miniclaude.domain.runtime.PolicyDecision;
import com.miniclaude.domain.scenario.SqlGuard;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** 三类 Profile 的确定性完成条件、参数和调用顺序控制。 */
@Component
public class ProfileHarnessControls implements HarnessCompletionVerifier, HarnessToolGuard {
    private static final Pattern PII = Pattern.compile(
            "(?i)[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}|(?<!\\d)1[3-9]\\d{9}(?!\\d)");
    private final SqlGuard sqlGuard = new SqlGuard();

    @Override
    public Verification verify(HarnessProfile profile, String finalText, Set<String> successfulTools) {
        if (finalText == null || finalText.trim().isEmpty()) {
            return Verification.reject("FINAL_TEXT_REQUIRED");
        }
        if (ScenarioCatalog.ANALYST.equals(profile.getId())
                && !successfulTools.containsAll(Arrays.asList(
                "sql_guard", "execute_read_only", "verify_citations", "emit_report_draft"))) {
            return Verification.reject("ANALYST_EVIDENCE_AND_REPORT_REQUIRED");
        }
        if (ScenarioCatalog.SUPPORT.equals(profile.getId())
                && !successfulTools.containsAll(Arrays.asList(
                "mask_pii", "knowledge_search", "check_compliance", "draft_reply"))) {
            return Verification.reject("SUPPORT_COMPLIANCE_DRAFT_REQUIRED");
        }
        if (ScenarioCatalog.CODING.equals(profile.getId())
                && !successfulTools.containsAll(Arrays.asList(
                "propose_patch", "run_tests", "review_patch", "emit_pr_draft"))) {
            return Verification.reject("CODING_TEST_REVIEW_REQUIRED");
        }
        return Verification.accept();
    }

    @Override
    public PolicyDecision evaluate(HarnessProfile profile, HarnessModelGateway.ToolCall call,
                                   Set<String> successfulTools) {
        if (ScenarioCatalog.ANALYST.equals(profile.getId())) {
            return analyst(call, successfulTools);
        }
        if (ScenarioCatalog.SUPPORT.equals(profile.getId())) {
            return support(call, successfulTools);
        }
        if (ScenarioCatalog.CODING.equals(profile.getId())) {
            return coding(call, successfulTools);
        }
        return PolicyDecision.allow("PROFILE_GUARD_NOT_APPLICABLE");
    }

    private PolicyDecision analyst(HarnessModelGateway.ToolCall call, Set<String> done) {
        String tool = call.getName();
        if (Arrays.asList("sql_guard", "estimate_query", "execute_read_only").contains(tool)) {
            try {
                sqlGuard.validate(text(call.getArguments(), "sql"),
                        number(call.getArguments(), "maxRows", 1000));
            } catch (RuntimeException invalid) {
                return PolicyDecision.deny("ANALYST_SQL_GUARD_FAILED");
            }
        }
        if ("estimate_query".equals(tool) && !done.contains("sql_guard")) {
            return PolicyDecision.deny("SQL_GUARD_MUST_PRECEDE_ESTIMATE");
        }
        if ("execute_read_only".equals(tool)
                && !done.containsAll(Arrays.asList("sql_guard", "estimate_query"))) {
            return PolicyDecision.deny("GUARD_AND_ESTIMATE_MUST_PRECEDE_QUERY");
        }
        if ("verify_citations".equals(tool) && !done.contains("execute_read_only")) {
            return PolicyDecision.deny("QUERY_MUST_PRECEDE_CITATION_VERIFY");
        }
        if ("emit_report_draft".equals(tool) && !done.contains("verify_citations")) {
            return PolicyDecision.deny("CITATION_VERIFY_MUST_PRECEDE_REPORT");
        }
        return PolicyDecision.allow("ANALYST_PROFILE_GUARD_PASSED");
    }

    private PolicyDecision support(HarnessModelGateway.ToolCall call, Set<String> done) {
        String tool = call.getName();
        if ("knowledge_search".equals(tool)) {
            if (!done.contains("mask_pii")) {
                return PolicyDecision.deny("PII_MASK_MUST_PRECEDE_RETRIEVAL");
            }
            String query = text(call.getArguments(), "question");
            if (PII.matcher(query).find()) return PolicyDecision.deny("RAW_PII_IN_RETRIEVAL");
        }
        if ("check_compliance".equals(tool) && !done.contains("knowledge_search")) {
            return PolicyDecision.deny("RETRIEVAL_MUST_PRECEDE_COMPLIANCE");
        }
        if ("draft_reply".equals(tool)
                && !done.containsAll(Arrays.asList("knowledge_search", "check_compliance"))) {
            return PolicyDecision.deny("COMPLIANCE_MUST_PRECEDE_DRAFT");
        }
        if ("request_human_handoff".equals(tool) && !done.contains("check_compliance")) {
            return PolicyDecision.deny("COMPLIANCE_MUST_PRECEDE_HANDOFF");
        }
        return PolicyDecision.allow("SUPPORT_PROFILE_GUARD_PASSED");
    }

    private PolicyDecision coding(HarnessModelGateway.ToolCall call, Set<String> done) {
        String combined = call.getArguments().values().toString().toLowerCase();
        String branch = String.valueOf(call.getArguments().getOrDefault("branch", ""));
        if ("main".equalsIgnoreCase(branch) || "master".equalsIgnoreCase(branch)
                || combined.contains("--no-verify") || combined.contains("push --force")
                || combined.contains("push -f") || combined.contains("production deploy")
                || combined.contains("deploy production") || combined.contains(".env")
                || combined.contains("credentials") || combined.contains("private_key")) {
            return PolicyDecision.deny("CODING_FORBIDDEN_TARGET_OR_SECRET");
        }
        String tool = call.getName();
        if ("propose_patch".equals(tool)
                && !done.containsAll(Arrays.asList("repo_search", "read_file"))) {
            return PolicyDecision.deny("EXPLORE_MUST_PRECEDE_PATCH");
        }
        if (Arrays.asList("run_build", "run_tests").contains(tool) && !done.contains("propose_patch")) {
            return PolicyDecision.deny("PATCH_MUST_PRECEDE_VERIFY");
        }
        if ("review_patch".equals(tool) && !done.contains("run_tests")) {
            return PolicyDecision.deny("TESTS_MUST_PRECEDE_REVIEW");
        }
        if ("emit_pr_draft".equals(tool) && !done.contains("review_patch")) {
            return PolicyDecision.deny("REVIEW_MUST_PRECEDE_PR_DRAFT");
        }
        return PolicyDecision.allow("CODING_PROFILE_GUARD_PASSED");
    }

    private static String text(Map<String, Object> values, String key) {
        Object value = values.get(key);
        if (value == null || value.toString().trim().isEmpty()) {
            throw new IllegalArgumentException(key + " required");
        }
        return value.toString().trim();
    }

    private static int number(Map<String, Object> values, String key, int fallback) {
        Object value = values.get(key);
        if (value == null) return fallback;
        return value instanceof Number
                ? ((Number) value).intValue() : Integer.parseInt(value.toString());
    }
}
