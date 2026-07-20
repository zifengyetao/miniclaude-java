package com.miniclaude.infrastructure.governance;

import com.google.gson.Gson;
import com.miniclaude.domain.governance.Evolver;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 可复现、无副作用的默认<b>自进化候选生成器</b>（{@link Evolver} 实现）。
 *
 * <p><b>为何默认确定性而非 LLM</b>：
 * <ul>
 *   <li>CI/评测需要相同输入 → 相同 patch → 相同 SHA-256，才能验证晋升状态机</li>
 *   <li>进化管线 L0–L3 禁止 L4；候选必须是<b>结构化 APPEND</b> 差异，不能整包覆盖生产资产</li>
 *   <li>本实现不访问 Registry、凭据、hidden holdout——模型替换实现也必须维持该边界</li>
 * </ul></p>
 *
 * <p>生产可替换为隔离环境中的模型适配器；替换后仍须人工评审 + 灰度 + 晋升。</p>
 */
@Component
public class DeterministicEvolver implements Evolver {
    /** Gson 用于生成稳定 JSON patch 文本 */
    private final Gson gson = new Gson();

    /**
     * 根据观察摘要生成 guarded guidance 追加提案。
     *
     * @param input 资产类型、适用条件、观察摘要等进化输入
     * @return 含 JSON diff、预期收益与回滚条件的 {@link Proposal}
     */
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
