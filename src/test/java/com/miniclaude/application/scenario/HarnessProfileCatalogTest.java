package com.miniclaude.application.scenario;

import com.miniclaude.domain.runtime.HarnessProfile;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HarnessProfileCatalogTest {
    private final HarnessProfileCatalog catalog = new HarnessProfileCatalog();

    @Test
    void exposesThreeProfilesWithoutIrreversibleCapabilities() {
        assertThat(catalog.list()).containsOnlyKeys(
                ScenarioCatalog.ANALYST, ScenarioCatalog.SUPPORT, ScenarioCatalog.CODING);

        HarnessProfile analyst = catalog.get(ScenarioCatalog.ANALYST);
        assertThat(analyst.getAllowedTools())
                .contains("execute_read_only")
                .doesNotContain("execute_sql_write");

        HarnessProfile support = catalog.get(ScenarioCatalog.SUPPORT);
        assertThat(support.getAllowedTools())
                .contains("draft_reply", "request_human_handoff")
                .doesNotContain("send_crm_message");

        HarnessProfile coding = catalog.get(ScenarioCatalog.CODING);
        assertThat(coding.getAllowedTools())
                .contains("propose_patch", "run_tests", "emit_pr_draft")
                .doesNotContain("apply_patch", "git_push", "deploy_production");
    }
}
