-- 受控进化记录从“经验”到“候选”再到“灰度/晋升”的证据链；任何表都不是直接改生产的捷径。
CREATE TABLE experience_observation (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(120) NOT NULL,
    source_type VARCHAR(64) NOT NULL,
    source_id VARCHAR(300) NOT NULL,
    trace_id VARCHAR(128),
    run_id VARCHAR(36),
    attribution_category VARCHAR(64) NOT NULL,
    summary TEXT NOT NULL,
    evidence_json TEXT NOT NULL,
    observed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    observed_by VARCHAR(200) NOT NULL
);
CREATE INDEX idx_observation_tenant_time ON experience_observation(tenant_id, observed_at);

CREATE TABLE evolution_candidate (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(120) NOT NULL,
    observation_id VARCHAR(36) NOT NULL,
    -- L0-L3 表示自动化权限边界，不是质量评分；状态由应用层有限状态机条件更新。
    maturity_level VARCHAR(8) NOT NULL,
    status VARCHAR(32) NOT NULL,
    asset_type VARCHAR(32) NOT NULL,
    change_class VARCHAR(32) NOT NULL,
    asset_key VARCHAR(200) NOT NULL,
    proposed_version VARCHAR(64) NOT NULL,
    parent_asset_id VARCHAR(36) NOT NULL,
    parent_asset_version VARCHAR(64) NOT NULL,
    provenance_json TEXT NOT NULL,
    attribution_category VARCHAR(64) NOT NULL,
    applicability TEXT NOT NULL,
    counterexamples TEXT NOT NULL,
    risk_class VARCHAR(32) NOT NULL,
    expected_benefit TEXT NOT NULL,
    patch_json TEXT NOT NULL,
    -- patch 在评测后、晋升前复算摘要；若中途被替换则晋升失败关闭。
    patch_hash VARCHAR(64) NOT NULL,
    proposer_id VARCHAR(200) NOT NULL,
    owner_id VARCHAR(200),
    regulated BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    promoted_asset_id VARCHAR(36),
    CONSTRAINT fk_candidate_observation FOREIGN KEY (observation_id) REFERENCES experience_observation(id),
    CONSTRAINT fk_candidate_parent FOREIGN KEY (parent_asset_id) REFERENCES versioned_asset(id),
    CONSTRAINT fk_candidate_promoted FOREIGN KEY (promoted_asset_id) REFERENCES versioned_asset(id),
    -- 同租户相同差异不重复形成候选，减少重放同一变更绕过复核的空间。
    CONSTRAINT uk_candidate_patch UNIQUE (tenant_id, patch_hash)
);
CREATE INDEX idx_candidate_tenant_status ON evolution_candidate(tenant_id, status, created_at);

CREATE TABLE candidate_evaluation (
    id VARCHAR(36) PRIMARY KEY,
    candidate_id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(120) NOT NULL,
    evaluator_id VARCHAR(200) NOT NULL,
    training_set_ref VARCHAR(300) NOT NULL,
    regression_set_ref VARCHAR(300) NOT NULL,
    -- 仅保存隔离 holdout 的引用/访问令牌摘要，不保存样本正文，防止生成器针对隐藏集过拟合。
    hidden_holdout_ref VARCHAR(300) NOT NULL,
    hidden_access_token_hash VARCHAR(64),
    suite_id VARCHAR(36) NOT NULL,
    manifest_id VARCHAR(36) NOT NULL,
    release_gate_id VARCHAR(36) NOT NULL,
    metrics_json TEXT NOT NULL,
    result VARCHAR(32) NOT NULL,
    result_hash VARCHAR(64) NOT NULL,
    evaluated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_candidate_eval_candidate FOREIGN KEY (candidate_id) REFERENCES evolution_candidate(id),
    CONSTRAINT fk_candidate_eval_suite FOREIGN KEY (suite_id) REFERENCES evaluation_suite(id),
    CONSTRAINT fk_candidate_eval_manifest FOREIGN KEY (manifest_id) REFERENCES agent_release_manifest(id),
    CONSTRAINT fk_candidate_eval_gate FOREIGN KEY (release_gate_id) REFERENCES release_gate(id)
);
CREATE INDEX idx_candidate_eval_candidate ON candidate_evaluation(candidate_id, evaluated_at);

CREATE TABLE candidate_review (
    id VARCHAR(36) PRIMARY KEY,
    candidate_id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(120) NOT NULL,
    reviewer_id VARCHAR(200) NOT NULL,
    reviewer_role VARCHAR(32) NOT NULL,
    decision VARCHAR(32) NOT NULL,
    comment_text TEXT,
    reviewed_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_candidate_review_candidate FOREIGN KEY (candidate_id) REFERENCES evolution_candidate(id)
);

CREATE TABLE rollout (
    id VARCHAR(36) PRIMARY KEY,
    candidate_id VARCHAR(36) NOT NULL,
    tenant_id VARCHAR(120) NOT NULL,
    stage VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    traffic_percent INTEGER NOT NULL,
    -- baseline 始终保留；rollback 撤销 target 而不覆盖/删除稳定父版本。
    baseline_asset_id VARCHAR(36) NOT NULL,
    target_asset_id VARCHAR(36),
    metrics_json TEXT NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    actor_id VARCHAR(200) NOT NULL,
    CONSTRAINT fk_rollout_candidate FOREIGN KEY (candidate_id) REFERENCES evolution_candidate(id),
    CONSTRAINT fk_rollout_baseline FOREIGN KEY (baseline_asset_id) REFERENCES versioned_asset(id),
    CONSTRAINT fk_rollout_target FOREIGN KEY (target_asset_id) REFERENCES versioned_asset(id)
);
CREATE INDEX idx_rollout_candidate_stage ON rollout(candidate_id, stage, started_at);

CREATE TABLE anti_rot_finding (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(120) NOT NULL,
    asset_id VARCHAR(36) NOT NULL,
    finding_type VARCHAR(64) NOT NULL,
    severity VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    evidence TEXT NOT NULL,
    -- finding 是人工/治理候选的输入，不触发自动删除或改写，避免启发式误判破坏生产。
    recommendation TEXT NOT NULL,
    detected_at TIMESTAMP WITH TIME ZONE NOT NULL,
    resolved_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_anti_rot_asset FOREIGN KEY (asset_id) REFERENCES versioned_asset(id)
);
CREATE INDEX idx_anti_rot_tenant_status ON anti_rot_finding(tenant_id, status, detected_at);
