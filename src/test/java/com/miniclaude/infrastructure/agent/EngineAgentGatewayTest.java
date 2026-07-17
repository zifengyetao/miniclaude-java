package com.miniclaude.infrastructure.agent;

import com.miniclaude.domain.agent.AgentSettings;
import com.miniclaude.domain.agent.ChatTurnResult;
import com.miniclaude.domain.runtime.AgentRuntime;
import com.miniclaude.domain.runtime.AgentRuntimeRequest;
import com.miniclaude.domain.runtime.AgentRuntimeResult;
import com.miniclaude.domain.runtime.ExecutionContext;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Collections;

import static org.assertj.core.api.Assertions.assertThat;

class EngineAgentGatewayTest {

    @Test
    void delegatesThroughRuntimePortWithUnchangedContext() {
        RecordingRuntime runtime = new RecordingRuntime();
        EngineAgentGateway gateway = new EngineAgentGateway(runtime);
        ExecutionContext context =
                new ExecutionContext(Paths.get("."), "tenant", "session", "run", "trace");
        AgentSettings settings = AgentSettings.builder().model("test-model").build();

        ChatTurnResult result = gateway.chat(context, settings, "hello");

        assertThat(runtime.request.getContext()).isSameAs(context);
        assertThat(runtime.request.getSettings()).isSameAs(settings);
        assertThat(runtime.request.getInput()).isEqualTo("hello");
        assertThat(result.getSessionId()).isEqualTo("session");
        assertThat(result.getReply()).isEqualTo("reply");
        assertThat(result.getTokens()).containsEntry("input", 2);
        assertThat(result.getModel()).isEqualTo("runtime-model");

        gateway.closeSession(context);
        assertThat(runtime.closedContext).isSameAs(context);
    }

    private static final class RecordingRuntime implements AgentRuntime {
        private AgentRuntimeRequest request;
        private ExecutionContext closedContext;

        @Override
        public AgentRuntimeResult execute(AgentRuntimeRequest request) {
            this.request = request;
            return new AgentRuntimeResult(
                    "reply", Collections.singletonMap("input", 2), "runtime-model");
        }

        @Override
        public void close(ExecutionContext context) {
            this.closedContext = context;
        }
    }
}
