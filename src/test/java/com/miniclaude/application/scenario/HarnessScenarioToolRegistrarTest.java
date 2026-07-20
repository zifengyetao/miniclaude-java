package com.miniclaude.application.scenario;

import com.miniclaude.domain.runtime.ExecutionContext;
import com.miniclaude.domain.runtime.ToolRequest;
import com.miniclaude.domain.runtime.ToolResult;
import com.miniclaude.infrastructure.runtime.ControlledToolGateway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:harness-tools;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "platform.orchestrator=local"
})
class HarnessScenarioToolRegistrarTest {
    @Autowired ControlledToolGateway tools;

    @Test
    void registersSafeDataSupportAndCodingToolsWithDefenseInDepth() {
        ToolResult unsafeSql = execute("sql_guard",
                map("sql", "DELETE FROM users LIMIT 10"));
        ToolResult expensive = execute("execute_read_only",
                map("sql", "SELECT * FROM expensive_table LIMIT 10"));
        ToolResult oversized = execute("sql_guard",
                map("sql", "SELECT * FROM users LIMIT 5000", "maxRows", 5000));
        ToolResult rawPii = execute("knowledge_search",
                map("question", "contact test@example.com"));
        ToolResult mainDraft = execute("emit_pr_draft",
                map("title", "change", "branch", "main"));
        ToolResult safeDraft = execute("emit_pr_draft",
                map("title", "change", "branch", "feature/safe"));

        assertThat(unsafeSql.isSuccessful()).isFalse();
        assertThat(unsafeSql.getOutput()).contains("TOOL_REJECTED").doesNotContain("DELETE");
        assertThat(expensive.isSuccessful()).isFalse();
        assertThat(expensive.getOutput()).contains("ANALYTICS_APPROVAL_REQUIRED");
        assertThat(oversized.isSuccessful()).isFalse();
        assertThat(oversized.getOutput()).contains("TOOL_REJECTED");
        assertThat(rawPii.isSuccessful()).isFalse();
        assertThat(rawPii.getOutput()).contains("RAW_PII_FORBIDDEN")
                .doesNotContain("test@example.com");
        assertThat(mainDraft.isSuccessful()).isFalse();
        assertThat(safeDraft.isSuccessful()).isTrue();
        assertThat(safeDraft.getOutput())
                .contains("\"externalPrCreated\":false", "\"pushed\":false");

        assertThatThrownBy(() -> execute("send_crm_message", map("message", "x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not registered");
    }

    private ToolResult execute(String name, Map<String, Object> arguments) {
        return tools.execute(new ToolRequest(
                new ExecutionContext(Paths.get(""), "default", "session", "run", "trace"),
                name, arguments));
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return values;
    }
}
