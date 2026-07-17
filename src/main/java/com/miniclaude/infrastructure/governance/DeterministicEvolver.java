package com.miniclaude.infrastructure.governance;

import com.google.gson.Gson;
import com.miniclaude.domain.governance.Evolver;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可复现、无副作用的默认候选生成器；生产可替换为隔离的模型适配器。
 *
 * <p>输出是结构化 APPEND 差异而非完整资产，也不访问 Registry、凭据或 hidden holdout。
 * 相同输入产生相同候选，便于测试 hash 与状态机；替换为模型时仍必须维持同一最小权限边界，
 * 模型故障只能导致候选生成失败，不能降级成直接写生产。</p>
 */
@Component
public class DeterministicEvolver implements Evolver {
    private final Gson gson = new Gson();

    @Override
    public Proposal propose(Input input) {
        // LinkedHashMap 固定字段顺序，使 JSON patch 及其 SHA-256 在不同运行间可复现。
        Map<String, String> diff = new LinkedHashMap<>();
        diff.put("operation", "APPEND_GUARDED_GUIDANCE");
        diff.put("assetType", input.getAssetType().name());
        diff.put("condition", input.getApplicability());
        diff.put("guidance", input.getObservationSummary());
        return new Proposal(gson.toJson(diff),
                "降低与该观察同类的失败率",
                "条件不匹配、证据不足或新指令与更高优先级约束冲突时不适用");
    }
}
