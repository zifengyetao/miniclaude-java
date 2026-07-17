package com.miniclaude.domain.governance;

/**
 * 候选生成端口。实现只能返回结构化差异，不能获得 Registry 或生产写能力。
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
