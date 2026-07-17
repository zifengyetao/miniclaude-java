CREATE TABLE scenario_artifact (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(120) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    artifact_type VARCHAR(80) NOT NULL,
    name VARCHAR(500) NOT NULL,
    content TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_scenario_artifact_run FOREIGN KEY (run_id) REFERENCES agent_run(id)
);

CREATE INDEX idx_scenario_artifact_run
    ON scenario_artifact(tenant_id, run_id, created_at);
