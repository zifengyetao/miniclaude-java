package com.miniclaude.application.scenario;

import com.miniclaude.domain.runtime.HarnessCompletionVerifier;
import com.miniclaude.domain.runtime.HarnessModelGateway;
import com.miniclaude.domain.runtime.HarnessProfile;
import com.miniclaude.domain.runtime.PolicyDecision;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class ProfileHarnessControlsTest {
    private final HarnessProfileCatalog catalog = new HarnessProfileCatalog();
    private final ProfileHarnessControls controls = new ProfileHarnessControls();

    @Test
    void analystRequiresGuardedReadOnlySequenceAndEvidenceBeforeCompletion() {
        HarnessProfile profile = catalog.get(ScenarioCatalog.ANALYST);
        PolicyDecision earlyQuery = controls.evaluate(profile,
                call("execute_read_only", map("sql", "SELECT * FROM t LIMIT 10")),
                Collections.emptySet());
        PolicyDecision unsafe = controls.evaluate(profile,
                call("sql_guard", map("sql", "DELETE FROM t LIMIT 10")),
                Collections.emptySet());

        assertThat(earlyQuery.isAllowed()).isFalse();
        assertThat(earlyQuery.getReason()).isEqualTo("GUARD_AND_ESTIMATE_MUST_PRECEDE_QUERY");
        assertThat(unsafe.isAllowed()).isFalse();
        assertThat(unsafe.getReason()).isEqualTo("ANALYST_SQL_GUARD_FAILED");

        HarnessCompletionVerifier.Verification rejected = controls.verify(
                profile, "looks done", set("sql_guard", "execute_read_only"));
        HarnessCompletionVerifier.Verification accepted = controls.verify(
                profile, "report with citations",
                set("sql_guard", "execute_read_only", "verify_citations", "emit_report_draft"));
        assertThat(rejected.isAccepted()).isFalse();
        assertThat(accepted.isAccepted()).isTrue();
    }

    @Test
    void supportRejectsRawPiiRetrievalAndRequiresComplianceDraft() {
        HarnessProfile profile = catalog.get(ScenarioCatalog.SUPPORT);
        PolicyDecision rawPii = controls.evaluate(profile,
                call("knowledge_search", map("question", "contact test@example.com")),
                set("mask_pii"));
        PolicyDecision draftTooEarly = controls.evaluate(profile,
                call("draft_reply", Collections.emptyMap()),
                set("mask_pii", "knowledge_search"));

        assertThat(rawPii.getReason()).isEqualTo("RAW_PII_IN_RETRIEVAL");
        assertThat(draftTooEarly.getReason()).isEqualTo("COMPLIANCE_MUST_PRECEDE_DRAFT");
        assertThat(controls.verify(profile, "draft",
                set("mask_pii", "knowledge_search", "check_compliance", "draft_reply"))
                .isAccepted()).isTrue();
    }

    @Test
    void codingRejectsProtectedBranchAndEnforcesTestReviewOrder() {
        HarnessProfile profile = catalog.get(ScenarioCatalog.CODING);
        PolicyDecision main = controls.evaluate(profile,
                call("propose_patch", map("branch", "main")),
                set("repo_search", "read_file"));
        PolicyDecision earlyReview = controls.evaluate(profile,
                call("review_patch", Collections.emptyMap()),
                set("repo_search", "read_file", "propose_patch"));

        assertThat(main.getReason()).isEqualTo("CODING_FORBIDDEN_TARGET_OR_SECRET");
        assertThat(earlyReview.getReason()).isEqualTo("TESTS_MUST_PRECEDE_REVIEW");
        assertThat(controls.verify(profile, "PR draft",
                set("propose_patch", "run_tests", "review_patch", "emit_pr_draft"))
                .isAccepted()).isTrue();
    }

    private static HarnessModelGateway.ToolCall call(String name, Map<String, Object> arguments) {
        return new HarnessModelGateway.ToolCall("call-" + name, name, arguments);
    }

    private static Set<String> set(String... values) {
        return new LinkedHashSet<>(Arrays.asList(values));
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return values;
    }
}
