package com.miniclaude.domain.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** 共享 Agent Harness 用例端口；所有 Profile 复用同一 Loop。 */
public interface AgentHarness {
    Result run(Request request);

    enum Status {
        COMPLETED,
        WAITING_APPROVAL,
        TURN_LIMIT,
        TOOL_LIMIT,
        FAILED
    }

    final class Request {
        private final ExecutionContext context;
        private final HarnessProfile profile;
        private final String model;
        private final String goal;

        public Request(ExecutionContext context, HarnessProfile profile, String model, String goal) {
            this.context = Objects.requireNonNull(context, "context");
            this.profile = Objects.requireNonNull(profile, "profile");
            this.model = text(model, "model");
            this.goal = text(goal, "goal");
        }

        public ExecutionContext getContext() { return context; }
        public HarnessProfile getProfile() { return profile; }
        public String getModel() { return model; }
        public String getGoal() { return goal; }
    }

    final class Result {
        private final Status status;
        private final String text;
        private final int turns;
        private final int toolCalls;
        private final Map<String, Integer> tokens;
        private final HarnessModelGateway.ToolCall pendingApproval;
        private final String reason;

        public Result(Status status, String text, int turns, int toolCalls,
                      Map<String, Integer> tokens,
                      HarnessModelGateway.ToolCall pendingApproval, String reason) {
            this.status = Objects.requireNonNull(status, "status");
            this.text = text == null ? "" : text;
            this.turns = turns;
            this.toolCalls = toolCalls;
            this.tokens = Collections.unmodifiableMap(new LinkedHashMap<>(
                    tokens == null ? Collections.emptyMap() : tokens));
            this.pendingApproval = pendingApproval;
            this.reason = reason == null ? "" : reason;
        }

        public Status getStatus() { return status; }
        public String getText() { return text; }
        public int getTurns() { return turns; }
        public int getToolCalls() { return toolCalls; }
        public Map<String, Integer> getTokens() { return tokens; }
        public HarnessModelGateway.ToolCall getPendingApproval() { return pendingApproval; }
        public String getReason() { return reason; }
    }

    static String text(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
