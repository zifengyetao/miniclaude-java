package com.miniclaude.application.platform;

import com.miniclaude.domain.runtime.AgentHarness;
import com.miniclaude.domain.runtime.HarnessCompletionVerifier;
import com.miniclaude.domain.runtime.HarnessEventSink;
import com.miniclaude.domain.runtime.HarnessModelGateway;
import com.miniclaude.domain.runtime.HarnessProfile;
import com.miniclaude.domain.runtime.HarnessToolGuard;
import com.miniclaude.domain.runtime.PolicyDecision;
import com.miniclaude.domain.runtime.PolicyEngine;
import com.miniclaude.domain.runtime.PolicyRequest;
import com.miniclaude.domain.runtime.ToolGateway;
import com.miniclaude.domain.runtime.ToolRequest;
import com.miniclaude.domain.runtime.ToolResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 供应商无关的共享 Agent Loop。
 *
 * <p>模型只提出 ToolCall；Harness 强制执行 Profile allowlist、Policy、预算和终止条件。
 * Graph 不参与动态循环。事件不包含模型思维链和完整工具输出。</p>
 */
@Service
public class DefaultAgentHarness implements AgentHarness {
    private final HarnessModelGateway models;
    private final ToolGateway tools;
    private final PolicyEngine policies;
    private final HarnessToolGuard toolGuard;
    private final HarnessCompletionVerifier completionVerifier;
    private final List<HarnessEventSink> sinks;

    public DefaultAgentHarness(HarnessModelGateway models, ToolGateway tools,
                               PolicyEngine policies, HarnessToolGuard toolGuard,
                               HarnessCompletionVerifier completionVerifier,
                               List<HarnessEventSink> sinks) {
        this.models = models;
        this.tools = tools;
        this.policies = policies;
        this.toolGuard = toolGuard;
        this.completionVerifier = completionVerifier;
        this.sinks = sinks == null ? Collections.emptyList() : new ArrayList<>(sinks);
    }

    @Override
    public Result run(Request request) {
        HarnessProfile profile = request.getProfile();
        List<String> transcript = new ArrayList<>();
        Map<String, Integer> tokens = new LinkedHashMap<>();
        Set<String> successfulTools = new LinkedHashSet<>();
        transcript.add("USER_GOAL: " + request.getGoal());
        int toolCallCount = 0;
        emit(request, HarnessEventSink.Type.RUN_STARTED, 0,
                map("profileVersion", profile.getVersion(), "autonomy", profile.getAutonomyMode().name()));

        int currentTurn = 0;
        try {
            for (int turn = 1; turn <= profile.getMaxTurns(); turn++) {
                currentTurn = turn;
                Prompt prompt = assemble(profile, request.getGoal(), transcript);
                emit(request, HarnessEventSink.Type.CONTEXT_ASSEMBLED, turn,
                        map("characters", prompt.value.length(), "entries", transcript.size()));
                if (prompt.compactedEntries > 0) {
                    emit(request, HarnessEventSink.Type.CONTEXT_COMPACTED, turn,
                            map("removedEntries", prompt.compactedEntries));
                }

                HarnessModelGateway.ModelTurn modelTurn = models.next(
                        new HarnessModelGateway.ModelTurnRequest(
                                request.getContext(), request.getModel(), profile.getId(),
                                turn, prompt.value));
                if (modelTurn == null) throw new IllegalStateException("model returned no turn");
                mergeTokens(tokens, modelTurn.getTokens());
                emit(request, HarnessEventSink.Type.MODEL_COMPLETED, turn,
                        map("toolCalls", modelTurn.getToolCalls().size(),
                                "textCharacters", modelTurn.getText().length()));

                if (!modelTurn.getText().trim().isEmpty()) {
                    transcript.add("ASSISTANT: " + modelTurn.getText().trim());
                }
                if (modelTurn.getToolCalls().isEmpty()) {
                    if (modelTurn.getText().trim().isEmpty()) {
                        return stopped(request, Status.FAILED, "", turn, toolCallCount,
                                tokens, "model produced neither text nor tool call",
                                HarnessEventSink.Type.RUN_FAILED);
                    }
                    HarnessCompletionVerifier.Verification verification = completionVerifier.verify(
                            profile, modelTurn.getText(), Collections.unmodifiableSet(successfulTools));
                    if (!verification.isAccepted()) {
                        transcript.add("COMPLETION_REJECTED: " + verification.getCode());
                        emit(request, HarnessEventSink.Type.COMPLETION_REJECTED, turn,
                                map("code", verification.getCode()));
                        continue;
                    }
                    emit(request, HarnessEventSink.Type.RUN_COMPLETED, turn,
                            map("turns", turn, "toolCalls", toolCallCount));
                    return new Result(Status.COMPLETED, modelTurn.getText(), turn,
                            toolCallCount, tokens, null, "");
                }

                for (HarnessModelGateway.ToolCall call : modelTurn.getToolCalls()) {
                    toolCallCount++;
                    emit(request, HarnessEventSink.Type.TOOL_REQUESTED, turn,
                            map("callId", call.getId(), "tool", call.getName()));
                    if (toolCallCount > profile.getMaxToolCalls()) {
                        return stopped(request, Status.TOOL_LIMIT, modelTurn.getText(), turn,
                                toolCallCount - 1, tokens, "tool call limit reached",
                                HarnessEventSink.Type.RUN_STOPPED);
                    }

                    String denied = denyReason(profile, call);
                    if (denied != null) {
                        transcript.add(observation(call, false, "DENIED: " + denied));
                        emit(request, HarnessEventSink.Type.TOOL_DENIED, turn,
                                map("callId", call.getId(), "tool", call.getName(), "reason", denied));
                        continue;
                    }

                    PolicyDecision guardDecision = toolGuard.evaluate(
                            profile, call, Collections.unmodifiableSet(successfulTools));
                    if (guardDecision == null || !guardDecision.isAllowed()) {
                        String code = guardDecision == null
                                ? "PROFILE_GUARD_NO_DECISION" : guardDecision.getReason();
                        transcript.add(observation(call, false, "DENIED: " + code));
                        emit(request, HarnessEventSink.Type.TOOL_DENIED, turn,
                                map("callId", call.getId(), "tool", call.getName(), "reason", code));
                        continue;
                    }

                    PolicyDecision decision = evaluatePolicy(request, call);
                    if (decision.isApprovalRequired()) {
                        emit(request, HarnessEventSink.Type.WAITING_APPROVAL, turn,
                                map("callId", call.getId(), "tool", call.getName(),
                                        "reason", decision.getReason()));
                        return new Result(Status.WAITING_APPROVAL, modelTurn.getText(), turn,
                                toolCallCount, tokens, call, decision.getReason());
                    }
                    if (!decision.isAllowed()) {
                        transcript.add(observation(call, false, "DENIED: " + decision.getReason()));
                        emit(request, HarnessEventSink.Type.TOOL_DENIED, turn,
                                map("callId", call.getId(), "tool", call.getName(),
                                        "reason", decision.getReason()));
                        continue;
                    }

                    emit(request, HarnessEventSink.Type.TOOL_ALLOWED, turn,
                            map("callId", call.getId(), "tool", call.getName()));
                    ToolResult result = tools.execute(new ToolRequest(
                            request.getContext(), call.getName(), call.getArguments()));
                    if (result == null) throw new IllegalStateException("tool returned no result");
                    if (result.isSuccessful()) successfulTools.add(call.getName());
                    transcript.add(observation(call, result.isSuccessful(), result.getOutput()));
                    emit(request, HarnessEventSink.Type.TOOL_COMPLETED, turn,
                            map("callId", call.getId(), "tool", call.getName(),
                                    "successful", result.isSuccessful(),
                                    "outputCharacters", result.getOutput().length()));
                }
            }
            return stopped(request, Status.TURN_LIMIT, "", profile.getMaxTurns(),
                    toolCallCount, tokens, "turn limit reached", HarnessEventSink.Type.RUN_STOPPED);
        } catch (RuntimeException failure) {
            return stopped(request, Status.FAILED, "", currentTurn, toolCallCount, tokens,
                    safeFailureCode(failure), HarnessEventSink.Type.RUN_FAILED);
        }
    }

    private String denyReason(HarnessProfile profile, HarnessModelGateway.ToolCall call) {
        if (profile.getAutonomyMode() == HarnessProfile.AutonomyMode.ADVISORY) {
            return "profile is advisory-only";
        }
        if (!profile.getAllowedTools().contains(call.getName())) {
            return "tool is outside profile allowlist";
        }
        return null;
    }

    private PolicyDecision evaluatePolicy(Request request, HarnessModelGateway.ToolCall call) {
        try {
            PolicyDecision decision = policies.evaluate(new PolicyRequest(
                    request.getContext(), "tool:" + call.getName(),
                    request.getProfile().getId() + "/" + call.getName(),
                    call.getArguments()));
            return decision == null ? PolicyDecision.deny("policy returned no decision") : decision;
        } catch (RuntimeException unavailable) {
            return PolicyDecision.deny("policy evaluation failed");
        }
    }

    private Prompt assemble(HarnessProfile profile, String goal, List<String> transcript) {
        List<String> selected = new ArrayList<>(transcript);
        String prompt = render(profile, goal, selected, 0);
        int removed = 0;
        while (prompt.length() > profile.getMaxContextChars() && selected.size() > 1) {
            selected.remove(1);
            removed++;
            prompt = render(profile, goal, selected, removed);
        }
        if (prompt.length() > profile.getMaxContextChars()) {
            int keep = Math.max(0, profile.getMaxContextChars() - 64);
            prompt = prompt.substring(0, Math.min(prompt.length(), keep))
                    + "\n[context truncated by harness]";
        }
        return new Prompt(prompt, removed);
    }

    private String render(HarnessProfile profile, String goal, List<String> transcript, int removed) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("PROFILE ").append(profile.getId()).append('@').append(profile.getVersion())
                .append('\n').append(profile.getSystemInstruction())
                .append("\nGOAL: ").append(goal).append('\n');
        if (removed > 0) prompt.append("[compacted ").append(removed).append(" earlier entries]\n");
        for (String entry : transcript) prompt.append(entry).append('\n');
        return prompt.toString();
    }

    private static String observation(HarnessModelGateway.ToolCall call,
                                      boolean successful, String output) {
        String normalized = output == null ? "" : output;
        if (normalized.length() > 4000) normalized = normalized.substring(0, 4000) + "[truncated]";
        return "TOOL_RESULT callId=" + call.getId() + " tool=" + call.getName()
                + " successful=" + successful + " output=" + normalized;
    }

    private Result stopped(Request request, Status status, String text, int turns, int toolCalls,
                           Map<String, Integer> tokens, String reason,
                           HarnessEventSink.Type eventType) {
        emit(request, eventType, turns, map("status", status.name(), "reason", reason));
        return new Result(status, text, turns, toolCalls, tokens, null, reason);
    }

    private void emit(Request request, HarnessEventSink.Type type, int turn,
                      Map<String, Object> attributes) {
        HarnessEventSink.Event event = new HarnessEventSink.Event(
                type, request.getContext(), request.getProfile().getId(), turn, attributes);
        for (HarnessEventSink sink : sinks) {
            try {
                sink.emit(event);
            } catch (RuntimeException ignored) {
                // 观测 Hook 不具备授权能力，失败不能改变 Tool Policy 决策或重复执行副作用。
            }
        }
    }

    private static void mergeTokens(Map<String, Integer> total, Map<String, Integer> increment) {
        for (Map.Entry<String, Integer> entry : increment.entrySet()) {
            total.merge(entry.getKey(), entry.getValue() == null ? 0 : entry.getValue(), Integer::sum);
        }
    }

    private static String safeFailureCode(RuntimeException failure) {
        if (failure instanceof IllegalArgumentException) return "HARNESS_INVALID_INPUT";
        if (failure instanceof SecurityException) return "HARNESS_SECURITY_REJECTED";
        return "HARNESS_EXECUTION_FAILED";
    }

    private static Map<String, Object> map(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put(String.valueOf(pairs[i]), pairs[i + 1]);
        }
        return values;
    }

    private static final class Prompt {
        private final String value;
        private final int compactedEntries;

        private Prompt(String value, int compactedEntries) {
            this.value = value;
            this.compactedEntries = compactedEntries;
        }
    }
}
