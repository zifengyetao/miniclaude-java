package com.miniclaude.application.governance;

import com.miniclaude.domain.runtime.HarnessEventSink;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 将 Harness 失败/预算停止转换成 L0 经验观察。
 *
 * <p>本 Hook 只能写 Observation，不能创建、评测或发布候选；后续仍须走 GovernedEvolutionService
 * 的职责分离和发布状态机。</p>
 */
@Component
public class GovernedHarnessObservationSink implements HarnessEventSink {
    private final GovernedEvolutionService evolution;
    private final boolean enabled;

    public GovernedHarnessObservationSink(
            GovernedEvolutionService evolution,
            @Value("${harness.evolution.observe-failures:true}") boolean enabled) {
        this.evolution = evolution;
        this.enabled = enabled;
    }

    @Override
    public void emit(Event event) {
        if (!enabled || (event.getType() != Type.RUN_FAILED
                && event.getType() != Type.RUN_STOPPED)) {
            return;
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("eventType", event.getType().name());
        evidence.put("profileId", event.getProfileId());
        evidence.put("turn", event.getTurn());
        evidence.put("status", event.getAttributes().get("status"));
        // 不记录用户输入、模型正文或工具输出，避免 Observation 成为 PII/密钥旁路。
        evolution.observe(
                event.getContext().getTenantId(),
                "HARNESS_PROFILE",
                event.getProfileId(),
                event.getContext().getTraceId(),
                event.getContext().getRunId(),
                "HARNESS_RUNTIME",
                "Harness run stopped: " + event.getProfileId() + "/"
                        + event.getAttributes().get("status"),
                evidence,
                "harness-observer");
    }
}
