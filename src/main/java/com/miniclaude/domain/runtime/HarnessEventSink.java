package com.miniclaude.domain.runtime;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Harness 结构化事件出口，用于 Trace、Eval、SSE 与受控进化 Observation。 */
public interface HarnessEventSink {
    void emit(Event event);

    enum Type {
        RUN_STARTED,
        CONTEXT_ASSEMBLED,
        CONTEXT_COMPACTED,
        MODEL_COMPLETED,
        TOOL_REQUESTED,
        TOOL_ALLOWED,
        TOOL_DENIED,
        TOOL_COMPLETED,
        WAITING_APPROVAL,
        COMPLETION_REJECTED,
        RUN_COMPLETED,
        RUN_STOPPED,
        RUN_FAILED
    }

    final class Event {
        private final Type type;
        private final ExecutionContext context;
        private final String profileId;
        private final int turn;
        private final Instant occurredAt;
        private final Map<String, Object> attributes;

        public Event(Type type, ExecutionContext context, String profileId, int turn,
                     Map<String, Object> attributes) {
            this.type = type;
            this.context = context;
            this.profileId = profileId;
            this.turn = turn;
            this.occurredAt = Instant.now();
            this.attributes = Collections.unmodifiableMap(new LinkedHashMap<>(
                    attributes == null ? Collections.emptyMap() : attributes));
        }

        public Type getType() { return type; }
        public ExecutionContext getContext() { return context; }
        public String getProfileId() { return profileId; }
        public int getTurn() { return turn; }
        public Instant getOccurredAt() { return occurredAt; }
        public Map<String, Object> getAttributes() { return attributes; }
    }
}
