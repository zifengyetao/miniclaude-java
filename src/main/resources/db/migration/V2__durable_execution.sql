ALTER TABLE agent_run ADD COLUMN tenant_id VARCHAR(120) NOT NULL DEFAULT 'default';
ALTER TABLE agent_run ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE agent_run ADD COLUMN cost_usd DECIMAL(18, 6) NOT NULL DEFAULT 0;
ALTER TABLE agent_run ADD COLUMN timeout_at TIMESTAMP WITH TIME ZONE;

CREATE INDEX idx_agent_run_tenant_status
    ON agent_run(tenant_id, status, updated_at);

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

CREATE INDEX idx_run_event_run_created
    ON run_event(tenant_id, run_id, created_at);
CREATE INDEX idx_run_event_step
    ON run_event(tenant_id, run_id, step_id, sequence_no);

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

CREATE INDEX idx_run_checkpoint_latest
    ON run_checkpoint(tenant_id, run_id, sequence_no);

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

CREATE INDEX idx_approval_pending
    ON approval_request(tenant_id, run_id, status, expires_at);
CREATE INDEX idx_approval_step
    ON approval_request(tenant_id, run_id, step_id, sequence_no);

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

CREATE INDEX idx_artifact_run
    ON artifact(tenant_id, run_id, sequence_no);
