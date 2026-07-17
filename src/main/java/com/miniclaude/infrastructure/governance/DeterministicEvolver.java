package com.miniclaude.infrastructure.governance;

import com.google.gson.Gson;
import com.miniclaude.domain.governance.Evolver;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** 可复现的默认实现；生产可替换为隔离的模型适配器。 */
@Component
public class DeterministicEvolver implements Evolver {
    private final Gson gson = new Gson();

    @Override
    public Proposal propose(Input input) {
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
