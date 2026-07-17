-- 为运行聚合补充租户隔离、乐观锁、累计成本和绝对超时；这些值在恢复后仍是硬边界。
ALTER TABLE agent_run ADD COLUMN tenant_id VARCHAR(120) NOT NULL DEFAULT 'default';
ALTER TABLE agent_run ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agent_run ADD COLUMN cost_usd DECIMAL(18, 6) NOT NULL DEFAULT 0;
ALTER TABLE agent_run ADD COLUMN timeout_at TIMESTAMP WITH TIME ZONE;

-- 支持按租户和状态扫描控制面任务，并按最近更新时间稳定排序。
CREATE INDEX idx_agent_run_tenant_status
    ON agent_run(tenant_id, status, updated_at);

-- 仅追加的运行事实流；序列唯一约束保证顺序，幂等唯一约束兜住并发重试。
-- payload_hash 用于拒绝“同一幂等键、不同载荷”的冲突重放。
CREATE TABLE run_event (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(120) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    step_id VARCHAR(200),
    sequence_no BIGINT NOT NULL,
    event_type VARCHAR(80) NOT NULL,
    idempotency_key VARCHAR(200) NOT NULL,
    payload TEXT NOT NULL,
    payload_hash VARCHAR(64) NOT NULL,
    version BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_run_event_run FOREIGN KEY (run_id) REFERENCES agent_run(id),
    CONSTRAINT uk_run_event_sequence UNIQUE (tenant_id, run_id, sequence_no),
    CONSTRAINT uk_run_event_idempotency UNIQUE (tenant_id, run_id, idempotency_key)
);

-- 运行历史查询通常按 tenant/run 过滤并按时间展示。
CREATE INDEX idx_run_event_run_created
    ON run_event(tenant_id, run_id, created_at);
-- 支持定位某一步的事件，同时保留运行内序列顺序。
CREATE INDEX idx_run_event_step
    ON run_event(tenant_id, run_id, step_id, sequence_no);

-- 不可变 checkpoint 历史；同一步允许递增版本，避免覆盖恢复和审计证据。
-- state_hash 检测状态载荷变化，不承担加密或访问控制职责。
CREATE TABLE run_checkpoint (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(120) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    step_id VARCHAR(200) NOT NULL,
    sequence_no BIGINT NOT NULL,
    version BIGINT NOT NULL,
    state_payload TEXT NOT NULL,
    state_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_run_checkpoint_run FOREIGN KEY (run_id) REFERENCES agent_run(id),
    CONSTRAINT uk_run_checkpoint_version UNIQUE (tenant_id, run_id, step_id, version)
);

-- 支持按运行内全局序列快速取得最近恢复点。
CREATE INDEX idx_run_checkpoint_latest
    ON run_checkpoint(tenant_id, run_id, sequence_no);

-- 人工审批记录；action_hash 将决定绑定到申请时的精确动作参数。
-- status/expiry/version 共同保证过期或并发重复决定时 fail-closed。
CREATE TABLE approval_request (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(120) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    step_id VARCHAR(200) NOT NULL,
    sequence_no BIGINT NOT NULL,
    version BIGINT NOT NULL,
    action_type VARCHAR(120) NOT NULL,
    action_parameters TEXT NOT NULL,
    action_hash VARCHAR(64) NOT NULL,
    status VARCHAR(32) NOT NULL,
    requested_at TIMESTAMP WITH TIME ZONE NOT NULL,
    expires_at TIMESTAMP WITH TIME ZONE NOT NULL,
    decided_at TIMESTAMP WITH TIME ZONE,
    decided_by VARCHAR(200),
    decision_reason VARCHAR(1000),
    CONSTRAINT fk_approval_run FOREIGN KEY (run_id) REFERENCES agent_run(id)
);

-- 恢复前快速判断是否仍有待决或已过期审批。
CREATE INDEX idx_approval_pending
    ON approval_request(tenant_id, run_id, status, expires_at);
-- 支持按步骤和全局序列审计审批历史。
CREATE INDEX idx_approval_step
    ON approval_request(tenant_id, run_id, step_id, sequence_no);

-- 持久产物只保存内容地址和摘要；版本唯一约束保留同名产物的演进历史。
CREATE TABLE artifact (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(120) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    step_id VARCHAR(200),
    sequence_no BIGINT NOT NULL,
    version BIGINT NOT NULL,
    artifact_type VARCHAR(80) NOT NULL,
    name VARCHAR(500) NOT NULL,
    content_uri VARCHAR(2000) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_artifact_run FOREIGN KEY (run_id) REFERENCES agent_run(id),
    CONSTRAINT uk_artifact_version UNIQUE (tenant_id, run_id, name, version)
);

-- 支持按运行内全局序列列出产物并与事件、checkpoint 时间线对齐。
CREATE INDEX idx_artifact_run
    ON artifact(tenant_id, run_id, sequence_no);
