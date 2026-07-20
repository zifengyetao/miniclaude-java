package com.miniclaude.application.governance;

import com.miniclaude.domain.runtime.ExecutionContext;
import com.miniclaude.domain.runtime.HarnessEventSink;
import org.junit.jupiter.api.Test;

import java.nio.file.Paths;
import java.util.Collections;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

class GovernedHarnessObservationSinkTest {

    @Test
    void recordsOnlyL0ObservationForFailedHarnessRun() {
        GovernedEvolutionService evolution = mock(GovernedEvolutionService.class);
        GovernedHarnessObservationSink sink = new GovernedHarnessObservationSink(evolution, true);
        ExecutionContext context = new ExecutionContext(
                Paths.get(""), "tenant-a", "session-a", "run-a", "trace-a");

        sink.emit(new HarnessEventSink.Event(
                HarnessEventSink.Type.RUN_FAILED, context, "coding-agent", 3,
                Collections.singletonMap("status", "FAILED")));

        verify(evolution).observe(
                eq("tenant-a"), eq("HARNESS_PROFILE"), eq("coding-agent"),
                eq("trace-a"), eq("run-a"), eq("HARNESS_RUNTIME"),
                eq("Harness run stopped: coding-agent/FAILED"),
                any(), eq("harness-observer"));
        // Sink 没有 propose/evaluate/promote API 调用路径，运行时失败只能形成 L0 Observation。
        verify(evolution, never()).candidates(any());
    }

    @Test
    void ignoresSuccessfulRuns() {
        GovernedEvolutionService evolution = mock(GovernedEvolutionService.class);
        GovernedHarnessObservationSink sink = new GovernedHarnessObservationSink(evolution, true);
        ExecutionContext context = new ExecutionContext(
                Paths.get(""), "tenant-a", "session-a", "run-a", "trace-a");

        sink.emit(new HarnessEventSink.Event(
                HarnessEventSink.Type.RUN_COMPLETED, context, "data-analyst", 2,
                Collections.singletonMap("status", "COMPLETED")));

        verify(evolution, never()).observe(
                any(), any(), any(), any(), any(), any(), any(), any(), any());
    }
}
