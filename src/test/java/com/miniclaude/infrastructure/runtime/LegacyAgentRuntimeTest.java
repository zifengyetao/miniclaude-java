package com.miniclaude.infrastructure.runtime;

import com.miniclaude.domain.agent.AgentSettings;
import com.miniclaude.domain.runtime.AgentRuntimeRequest;
import com.miniclaude.domain.runtime.AgentRuntimeResult;
import com.miniclaude.domain.runtime.ExecutionContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LegacyAgentRuntimeTest {

    @Test
    void adaptsLegacyResultReusesSessionAndClosesIt() {
        AtomicInteger creations = new AtomicInteger();
        AtomicBoolean closed = new AtomicBoolean();
        LegacyAgentRuntime runtime = new LegacyAgentRuntime(settings -> {
            creations.incrementAndGet();
            return new LegacyAgentRuntime.LegacyAgentSession() {
                @Override
                public Map<String, Object> runOnce(String input) {
                    Map<String, Object> tokens = new LinkedHashMap<>();
                    tokens.put("input", 3L);
                    tokens.put("output", "5");
                    Map<String, Object> result = new LinkedHashMap<>();
                    result.put("text", "echo:" + input);
                    result.put("tokens", tokens);
                    return result;
                }

                @Override
                public void close() {
                    closed.set(true);
                }
            };
        });
        ExecutionContext context = context(Paths.get(""));
        AgentSettings settings = settings("key");

        AgentRuntimeResult first =
                runtime.execute(new AgentRuntimeRequest(context, settings, "hello"));
        runtime.execute(new AgentRuntimeRequest(context, settings, "again"));

        assertThat(first.getText()).isEqualTo("echo:hello");
        assertThat(first.getTokens()).containsEntry("input", 3).containsEntry("output", 5);
        assertThat(creations).hasValue(1);

        runtime.close(context);
        assertThat(closed).isTrue();
    }

    @Test
    void failsClosedBeforeCreatingAgentWhenApiKeyIsMissing() {
        AtomicInteger creations = new AtomicInteger();
        LegacyAgentRuntime runtime = new LegacyAgentRuntime(settings -> {
            creations.incrementAndGet();
            throw new AssertionError("must not create");
        });

        assertThatThrownBy(() -> runtime.execute(new AgentRuntimeRequest(
                context(Paths.get("")), settings(""), "hello")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("API key is required");
        assertThat(creations).hasValue(0);
    }

    @Test
    void failsClosedForWorkspaceNotSupportedByLegacyEngine(@TempDir Path otherWorkspace) {
        LegacyAgentRuntime runtime = new LegacyAgentRuntime(settings -> {
            throw new AssertionError("must not create");
        });

        assertThatThrownBy(() -> runtime.execute(new AgentRuntimeRequest(
                context(otherWorkspace), settings("key"), "hello")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("workspace is disabled");
    }

    private static ExecutionContext context(Path workspace) {
        return new ExecutionContext(workspace, "tenant", "session", "run", "trace");
    }

    private static AgentSettings settings(String apiKey) {
        return AgentSettings.builder()
                .model("test-model")
                .apiKey(apiKey)
                .permissionMode("default")
                .build();
    }
}
