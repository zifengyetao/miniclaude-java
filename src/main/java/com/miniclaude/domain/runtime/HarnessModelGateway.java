package com.miniclaude.domain.runtime;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 面向 Agent Loop 的模型端口，显式表达文本或结构化 Tool Calls。
 *
 * <p>与简单 {@link ModelGateway} 不同，本端口不要求 Harness 解析供应商私有 JSON。</p>
 */
public interface HarnessModelGateway {
    ModelTurn next(ModelTurnRequest request);

    final class ModelTurnRequest {
        private final ExecutionContext context;
        private final String model;
        private final String profileId;
        private final int turn;
        private final String prompt;

        public ModelTurnRequest(ExecutionContext context, String model, String profileId,
                                int turn, String prompt) {
            this.context = Objects.requireNonNull(context, "context");
            this.model = text(model, "model");
            this.profileId = text(profileId, "profileId");
            this.turn = turn;
            this.prompt = text(prompt, "prompt");
        }

        public ExecutionContext getContext() { return context; }
        public String getModel() { return model; }
        public String getProfileId() { return profileId; }
        public int getTurn() { return turn; }
        public String getPrompt() { return prompt; }
    }

    final class ModelTurn {
        private final String text;
        private final List<ToolCall> toolCalls;
        private final Map<String, Integer> tokens;

        public ModelTurn(String text, List<ToolCall> toolCalls, Map<String, Integer> tokens) {
            this.text = text == null ? "" : text;
            this.toolCalls = Collections.unmodifiableList(new ArrayList<>(
                    toolCalls == null ? Collections.emptyList() : toolCalls));
            this.tokens = Collections.unmodifiableMap(new LinkedHashMap<>(
                    tokens == null ? Collections.emptyMap() : tokens));
        }

        public static ModelTurn finalText(String text) {
            return new ModelTurn(text, Collections.emptyList(), Collections.emptyMap());
        }

        public String getText() { return text; }
        public List<ToolCall> getToolCalls() { return toolCalls; }
        public Map<String, Integer> getTokens() { return tokens; }
    }

    final class ToolCall {
        private final String id;
        private final String name;
        private final Map<String, Object> arguments;

        public ToolCall(String id, String name, Map<String, Object> arguments) {
            this.id = text(id, "toolCall.id");
            this.name = text(name, "toolCall.name");
            this.arguments = Collections.unmodifiableMap(new LinkedHashMap<>(
                    arguments == null ? Collections.emptyMap() : arguments));
        }

        public String getId() { return id; }
        public String getName() { return name; }
        public Map<String, Object> getArguments() { return arguments; }
    }

    static String text(String value, String name) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalArgumentException(name + " is required");
        }
        return value.trim();
    }
}
