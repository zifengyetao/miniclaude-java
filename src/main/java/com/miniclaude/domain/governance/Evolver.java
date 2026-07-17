package com.miniclaude.domain.governance;

/**
 * 候选生成端口。实现只能返回结构化差异，不能获得 Registry 或生产写能力。
 *
 * <p>这是受控进化最重要的权限边界：生成器只能提出 patch、收益假设和反例，不能发布资产、
 * 读取 hidden holdout，也不能绕过评测、复核、shadow、canary 状态机。即使生成器失控，
 * 最坏结果也应是产生一个待审候选，而不是直接修改生产基线。</p>
 */
public interface Evolver {
    Proposal propose(Input input);

    final class Input {
        private final String observationSummary;
        private final String parentContent;
        private final VersionedAsset.Type assetType;
        private final String applicability;

        public Input(String observationSummary, String parentContent, VersionedAsset.Type assetType,
                     String applicability) {
            this.observationSummary = observationSummary;
            this.parentContent = parentContent;
            this.assetType = assetType;
            this.applicability = applicability;
        }

        public String getObservationSummary() { return observationSummary; }
        public String getParentContent() { return parentContent; }
        public VersionedAsset.Type getAssetType() { return assetType; }
        public String getApplicability() { return applicability; }
    }

    final class Proposal {
        /** 待校验的结构化差异；入库后以 hash 固定，晋升前会复算以发现中途篡改。 */
        private final String patch;
        private final String expectedBenefit;
        private final String counterexamples;

        public Proposal(String patch, String expectedBenefit, String counterexamples) {
            this.patch = patch;
            this.expectedBenefit = expectedBenefit;
            this.counterexamples = counterexamples;
        }

        public String getPatch() { return patch; }
        public String getExpectedBenefit() { return expectedBenefit; }
        public String getCounterexamples() { return counterexamples; }
    }
}
