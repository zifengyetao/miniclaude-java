package com.miniclaude.application.platform;

import com.miniclaude.domain.runtime.AgentHarness;
import com.miniclaude.domain.runtime.ExecutionContext;
import com.miniclaude.domain.runtime.HarnessEventSink;
import com.miniclaude.domain.runtime.HarnessModelGateway;
import com.miniclaude.domain.runtime.HarnessProfile;
import com.miniclaude.domain.runtime.PolicyDecision;
import com.miniclaude.domain.runtime.PolicyRequest;
import com.miniclaude.domain.runtime.ToolResult;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class DefaultAgentHarnessTest {

    @Test
    void completesSharedLoopAfterAllowedToolObservation() {
        ScriptedModel model = new ScriptedModel(
                turn("", new HarnessModelGateway.ToolCall(
                        "c1", "search", Collections.singletonMap("query", "revenue"))),
                HarnessModelGateway.ModelTurn.finalText("final answer"));
        AtomicInteger executions = new AtomicInteger();
        AtomicReference<PolicyRequest> policyRequest = new AtomicReference<>();
        List<HarnessEventSink.Event> events = new ArrayList<>();
        DefaultAgentHarness harness = new DefaultAgentHarness(
                model,
                request -> {
                    executions.incrementAndGet();
                    return new ToolResult(true, "evidence-1");
                },
                request -> {
                    policyRequest.set(request);
                    return PolicyDecision.allow("read-only");
                },
                (profile, call, done) -> PolicyDecision.allow("profile"),
                (profile, text, done) -> com.miniclaude.domain.runtime.HarnessCompletionVerifier.Verification.accept(),
                Collections.singletonList(events::add));

        AgentHarness.Result result = harness.run(request(profile(
                HarnessProfile.AutonomyMode.READ_ONLY_AUTO, 4, 4, 2000)));

        assertThat(result.getStatus()).isEqualTo(AgentHarness.Status.COMPLETED);
        assertThat(result.getText()).isEqualTo("final answer");
        assertThat(result.getTurns()).isEqualTo(2);
        assertThat(result.getToolCalls()).isEqualTo(1);
        assertThat(executions).hasValue(1);
        assertThat(policyRequest.get().getArguments()).containsEntry("query", "revenue");
        assertThat(model.requests.get(1).getPrompt()).contains("evidence-1");
        assertThat(events.stream().map(HarnessEventSink.Event::getType))
                .contains(HarnessEventSink.Type.RUN_STARTED,
                        HarnessEventSink.Type.TOOL_ALLOWED,
                        HarnessEventSink.Type.TOOL_COMPLETED,
                        HarnessEventSink.Type.RUN_COMPLETED);
    }

    @Test
    void deniesToolOutsideProfileWithoutExecutingIt() {
        ScriptedModel model = new ScriptedModel(
                turn("", call("c1", "write_database")),
                HarnessModelGateway.ModelTurn.finalText("cannot perform write"));
        AtomicInteger executions = new AtomicInteger();
        DefaultAgentHarness harness = new DefaultAgentHarness(
                model,
                request -> {
                    executions.incrementAndGet();
                    return new ToolResult(true, "should-not-run");
                },
                request -> PolicyDecision.allow("unused"),
                (profile, call, done) -> PolicyDecision.allow("profile"),
                (profile, text, done) -> com.miniclaude.domain.runtime.HarnessCompletionVerifier.Verification.accept(),
                Collections.emptyList());

        AgentHarness.Result result = harness.run(request(profile(
                HarnessProfile.AutonomyMode.CONTROLLED, 4, 4, 2000)));

        assertThat(result.getStatus()).isEqualTo(AgentHarness.Status.COMPLETED);
        assertThat(executions).hasValue(0);
        assertThat(model.requests.get(1).getPrompt())
                .contains("DENIED: tool is outside profile allowlist");
    }

    @Test
    void pausesBeforeToolWhenPolicyRequiresApproval() {
        ScriptedModel model = new ScriptedModel(turn("", call("c1", "search")));
        AtomicInteger executions = new AtomicInteger();
        DefaultAgentHarness harness = new DefaultAgentHarness(
                model,
                request -> {
                    executions.incrementAndGet();
                    return new ToolResult(true, "should-not-run");
                },
                request -> PolicyDecision.requireApproval("high cost"),
                (profile, call, done) -> PolicyDecision.allow("profile"),
                (profile, text, done) -> com.miniclaude.domain.runtime.HarnessCompletionVerifier.Verification.accept(),
                Collections.emptyList());

        AgentHarness.Result result = harness.run(request(profile(
                HarnessProfile.AutonomyMode.CONTROLLED, 4, 4, 2000)));

        assertThat(result.getStatus()).isEqualTo(AgentHarness.Status.WAITING_APPROVAL);
        assertThat(result.getPendingApproval().getName()).isEqualTo("search");
        assertThat(result.getReason()).isEqualTo("high cost");
        assertThat(executions).hasValue(0);
    }

    @Test
    void compactsOldToolObservationWithinContextBudget() {
        ScriptedModel model = new ScriptedModel(
                turn("", call("c1", "search")),
                HarnessModelGateway.ModelTurn.finalText("done"));
        List<HarnessEventSink.Event> events = new ArrayList<>();
        DefaultAgentHarness harness = new DefaultAgentHarness(
                model,
                request -> new ToolResult(true, repeat("x", 5000)),
                request -> PolicyDecision.allow("read-only"),
                (profile, call, done) -> PolicyDecision.allow("profile"),
                (profile, text, done) -> com.miniclaude.domain.runtime.HarnessCompletionVerifier.Verification.accept(),
                Collections.singletonList(events::add));

        AgentHarness.Result result = harness.run(request(profile(
                HarnessProfile.AutonomyMode.READ_ONLY_AUTO, 4, 4, 512)));

        assertThat(result.getStatus()).isEqualTo(AgentHarness.Status.COMPLETED);
        assertThat(model.requests.get(1).getPrompt().length()).isLessThanOrEqualTo(512);
        assertThat(events.stream().map(HarnessEventSink.Event::getType))
                .contains(HarnessEventSink.Type.CONTEXT_COMPACTED);
    }

    @Test
    void continuesLoopWhenCompletionVerifierRejectsPrematureAnswer() {
        ScriptedModel model = new ScriptedModel(
                HarnessModelGateway.ModelTurn.finalText("premature"),
                HarnessModelGateway.ModelTurn.finalText("verified"));
        AtomicInteger verifications = new AtomicInteger();
        List<HarnessEventSink.Event> events = new ArrayList<>();
        DefaultAgentHarness harness = new DefaultAgentHarness(
                model,
                request -> new ToolResult(true, ""),
                request -> PolicyDecision.allow("unused"),
                (profile, call, done) -> PolicyDecision.allow("profile"),
                (profile, text, done) -> verifications.incrementAndGet() == 1
                        ? com.miniclaude.domain.runtime.HarnessCompletionVerifier.Verification.reject(
                        "EVIDENCE_REQUIRED")
                        : com.miniclaude.domain.runtime.HarnessCompletionVerifier.Verification.accept(),
                Collections.singletonList(events::add));

        AgentHarness.Result result = harness.run(request(profile(
                HarnessProfile.AutonomyMode.READ_ONLY_AUTO, 3, 2, 2000)));

        assertThat(result.getStatus()).isEqualTo(AgentHarness.Status.COMPLETED);
        assertThat(result.getText()).isEqualTo("verified");
        assertThat(result.getTurns()).isEqualTo(2);
        assertThat(model.requests.get(1).getPrompt()).contains("COMPLETION_REJECTED: EVIDENCE_REQUIRED");
        assertThat(events.stream().map(HarnessEventSink.Event::getType))
                .contains(HarnessEventSink.Type.COMPLETION_REJECTED);
    }

    @Test
    void returnsStableFailureCodeWithoutLeakingExceptionMessage() {
        DefaultAgentHarness harness = new DefaultAgentHarness(
                request -> {
                    throw new IllegalStateException("api_key=secret-value");
                },
                request -> new ToolResult(true, ""),
                request -> PolicyDecision.allow("unused"),
                (profile, call, done) -> PolicyDecision.allow("profile"),
                (profile, text, done) -> com.miniclaude.domain.runtime.HarnessCompletionVerifier.Verification.accept(),
                Collections.emptyList());

        AgentHarness.Result result = harness.run(request(profile(
                HarnessProfile.AutonomyMode.READ_ONLY_AUTO, 2, 2, 2000)));

        assertThat(result.getStatus()).isEqualTo(AgentHarness.Status.FAILED);
        assertThat(result.getReason()).isEqualTo("HARNESS_EXECUTION_FAILED");
        assertThat(result.getReason()).doesNotContain("secret-value");
    }

    private static AgentHarness.Request request(HarnessProfile profile) {
        return new AgentHarness.Request(
                new ExecutionContext(Paths.get(""), "tenant-a", "session-a", "run-a", "trace-a"),
                profile, "scripted", "investigate issue");
    }

    private static HarnessProfile profile(HarnessProfile.AutonomyMode mode,
                                          int maxTurns, int maxTools, int maxContext) {
        return new HarnessProfile("test-profile", "1.0.0", "Use safe tools only.",
                new LinkedHashSet<>(Collections.singletonList("search")),
                mode, maxTurns, maxTools, maxContext);
    }

    private static HarnessModelGateway.ModelTurn turn(
            String text, HarnessModelGateway.ToolCall... calls) {
        return new HarnessModelGateway.ModelTurn(
                text, Arrays.asList(calls), Collections.singletonMap("input", 10));
    }

    private static HarnessModelGateway.ToolCall call(String id, String name) {
        return new HarnessModelGateway.ToolCall(id, name, Collections.emptyMap());
    }

    private static String repeat(String value, int count) {
        StringBuilder result = new StringBuilder(value.length() * count);
        for (int i = 0; i < count; i++) result.append(value);
        return result.toString();
    }

    private static final class ScriptedModel implements HarnessModelGateway {
        private final Queue<ModelTurn> turns = new ArrayDeque<>();
        private final List<ModelTurnRequest> requests = new ArrayList<>();

        private ScriptedModel(ModelTurn... turns) {
            this.turns.addAll(Arrays.asList(turns));
        }

        @Override
        public ModelTurn next(ModelTurnRequest request) {
            requests.add(request);
            ModelTurn turn = turns.poll();
            if (turn == null) throw new IllegalStateException("script exhausted");
            return turn;
        }
    }
}
