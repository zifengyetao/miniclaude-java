package com.miniclaude.domain.governance;

/**
 * 候选生成 Outbound Port（受控进化链路的「提议」端）。
 * <p>
 * <b>为何放在 domain：</b>进化流程需要「只能提议、不能发布」的明确端口边界，防止 LLM 直接改生产。
 * <p>
 * <b>不变量：</b>实现仅返回 {@link Proposal}；不得写 Registry、不得读 hidden holdout、不得绕过评测状态机。
 * <p>
 * <b>边界：</b>infrastructure LLM/脚本 Evolver 实现；application GovernedEvolutionController 编排 L0–L3 gate。
 */
public interface Evolver {

    /**
     * 基于观测与父资产内容生成进化候选。
     *
     * @param input 观测摘要、父内容、资产类型、适用范围
     * @return 结构化 patch + 收益假设 + 反例（待评测，非生产写入）
     */
    Proposal propose(Input input);

    /** 进化器输入（不可变 DTO）。 */
    final class Input {
        /** 运行观测/反馈摘要（脱敏后）。 */
        private final String observationSummary;
        /** 父版本资产正文。 */
        private final String parentContent;
        /** 目标资产类型。 */
        private final VersionedAsset.Type assetType;
        /** 适用场景/租户范围描述。 */
        private final String applicability;

        public Input(String observationSummary, String parentContent, VersionedAsset.Type assetType,
                     String applicability) {
            this.observationSummary = observationSummary;
            this.parentContent = parentContent;
            this.assetType = assetType;
            this.applicability = applicability;
        }

        /** @return 观测摘要 */
        public String getObservationSummary() { return observationSummary; }
        /** @return 父资产内容 */
        public String getParentContent() { return parentContent; }
        /** @return 资产类型 */
        public VersionedAsset.Type getAssetType() { return assetType; }
        /** @return 适用范围 */
        public String getApplicability() { return applicability; }
    }

    /** 进化候选提议（不可变 DTO）。 */
    final class Proposal {
        /** 待校验的结构化差异（JSON/YAML patch）；入库后以 hash 固定。 */
        private final String patch;
        /** 预期收益说明（供人工/自动评测）。 */
        private final String expectedBenefit;
        /** 反例/回归风险（强制进化反思）。 */
        private final String counterexamples;

        public Proposal(String patch, String expectedBenefit, String counterexamples) {
            this.patch = patch;
            this.expectedBenefit = expectedBenefit;
            this.counterexamples = counterexamples;
        }

        /** @return patch 正文 */
        public String getPatch() { return patch; }
        /** @return 预期收益 */
        public String getExpectedBenefit() { return expectedBenefit; }
        /** @return 反例说明 */
        public String getCounterexamples() { return counterexamples; }
    }
}
