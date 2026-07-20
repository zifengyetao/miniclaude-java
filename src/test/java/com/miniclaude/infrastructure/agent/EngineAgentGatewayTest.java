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

/**
 * 验证聊天网关只做形态转换，并完整保留运行时安全上下文。
 *
 * <p>记录型运行时隔离具体引擎副作用，使测试聚焦适配器契约。
 */
class EngineAgentGatewayTest {

    /** chat 应透传 ExecutionContext/AgentSettings 引用并映射 ChatTurnResult 字段。 */
    @Test
    void delegatesThroughRuntimePortWithUnchangedContext() {
        RecordingRuntime runtime = new RecordingRuntime();
        EngineAgentGateway gateway = new EngineAgentGateway(runtime);
        ExecutionContext context =
                new ExecutionContext(Paths.get("."), "tenant", "session", "run", "trace");
        AgentSettings settings = AgentSettings.builder().model("test-model").build();

        ChatTurnResult result = gateway.chat(context, settings, "hello");

        // 要求对象身份不变，防止适配层重建上下文时遗漏租户或追踪字段。
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
