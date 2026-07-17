-- AgentOps 治理基表。设计原则是“版本追加、精确解析、证据留存”，而非原地覆盖配置。
CREATE TABLE versioned_asset (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(120) NOT NULL,
    asset_type VARCHAR(32) NOT NULL,
    asset_key VARCHAR(200) NOT NULL,
    version VARCHAR(64) NOT NULL,
    parent_id VARCHAR(36),
    status VARCHAR(32) NOT NULL,
    content TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    signature VARCHAR(500),
    created_by VARCHAR(200) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    published_at TIMESTAMP WITH TIME ZONE,
    deprecated_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_asset_parent FOREIGN KEY (parent_id) REFERENCES versioned_asset(id),
    -- 同一治理坐标不可重建；修订必须使用新 version，并可通过 parent_id 保留谱系。
    CONSTRAINT uk_asset_version UNIQUE (tenant_id, asset_type, asset_key, version)
);
CREATE INDEX idx_asset_resolve ON versioned_asset(tenant_id, asset_type, asset_key, version, status);

CREATE TABLE agent_release_manifest (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(120) NOT NULL,
    agent_key VARCHAR(200) NOT NULL,
    version VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    -- 保存 key@精确版本#内容哈希，不保存 latest/范围版本，确保发布可复现。
    assets_json TEXT NOT NULL,
    manifest_hash VARCHAR(64) NOT NULL,
    signature VARCHAR(500),
    created_by VARCHAR(200) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    released_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT uk_manifest_version UNIQUE (tenant_id, agent_key, version)
);

CREATE TABLE policy_rule (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(120) NOT NULL,
    rule_key VARCHAR(200) NOT NULL,
    version VARCHAR(64) NOT NULL,
    scope VARCHAR(500) NOT NULL,
    action_pattern VARCHAR(500) NOT NULL,
    resource_pattern VARCHAR(1000) NOT NULL,
    -- priority 用于确定性排序；运行时 DENY 仍拥有跨优先级否决权（deny-first）。
    priority INTEGER NOT NULL,
    effect VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_policy_rule_version UNIQUE (tenant_id, rule_key, version)
);
CREATE INDEX idx_policy_evaluate ON policy_rule(tenant_id, enabled, priority);

CREATE TABLE audit_event (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(120) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    actor_type VARCHAR(64) NOT NULL,
    actor_id VARCHAR(200) NOT NULL,
    operation VARCHAR(120) NOT NULL,
    resource_type VARCHAR(64) NOT NULL,
    resource_id VARCHAR(300) NOT NULL,
    decision VARCHAR(64),
    -- 不落原始 payload；previous_hash 将同租户事件串成可验证链，历史篡改会传导到后续摘要。
    payload_hash VARCHAR(64) NOT NULL,
    previous_hash VARCHAR(64),
    event_hash VARCHAR(64) NOT NULL,
    trace_id VARCHAR(128),
    run_id VARCHAR(36)
);
CREATE INDEX idx_audit_query ON audit_event(tenant_id, occurred_at, resource_type, resource_id);

CREATE TABLE evaluation_suite (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(120) NOT NULL,
    suite_key VARCHAR(200) NOT NULL,
    version VARCHAR(64) NOT NULL,
    thresholds_json TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT uk_eval_suite_version UNIQUE (tenant_id, suite_key, version)
);

CREATE TABLE evaluation_run (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(120) NOT NULL,
    suite_id VARCHAR(36) NOT NULL,
    manifest_id VARCHAR(36) NOT NULL,
    status VARCHAR(32) NOT NULL,
    metrics_json TEXT NOT NULL,
    -- safety_passed 是 release gate 的硬否决输入，不能由其他优秀指标抵消。
    safety_passed BOOLEAN NOT NULL,
    result_hash VARCHAR(64) NOT NULL,
    started_at TIMESTAMP WITH TIME ZONE NOT NULL,
    completed_at TIMESTAMP WITH TIME ZONE,
    CONSTRAINT fk_eval_suite FOREIGN KEY (suite_id) REFERENCES evaluation_suite(id),
    CONSTRAINT fk_eval_manifest FOREIGN KEY (manifest_id) REFERENCES agent_release_manifest(id)
);

CREATE TABLE release_gate (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(120) NOT NULL,
    evaluation_run_id VARCHAR(36) NOT NULL,
    manifest_id VARCHAR(36) NOT NULL,
    -- 每次评测恰有一个门禁结论；只有 PASS 才能作为后续受控发布证据。
    decision VARCHAR(32) NOT NULL,
    reasons TEXT NOT NULL,
    decided_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_gate_run FOREIGN KEY (evaluation_run_id) REFERENCES evaluation_run(id),
    CONSTRAINT fk_gate_manifest FOREIGN KEY (manifest_id) REFERENCES agent_release_manifest(id),
    CONSTRAINT uk_gate_run UNIQUE (tenant_id, evaluation_run_id)
);
