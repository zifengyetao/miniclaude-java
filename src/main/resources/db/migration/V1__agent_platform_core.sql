CREATE TABLE agent_definition (
    id VARCHAR(36) PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    role_name VARCHAR(120) NOT NULL,
    risk_level VARCHAR(32) NOT NULL,
    evolution_level VARCHAR(8) NOT NULL,
    status VARCHAR(32) NOT NULL,
    version VARCHAR(32) NOT NULL,
    execution_modes VARCHAR(500) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
);

CREATE UNIQUE INDEX uk_agent_definition_name_version
    ON agent_definition(name, version);

CREATE TABLE agent_run (
    id VARCHAR(36) PRIMARY KEY,
    agent_id VARCHAR(36) NOT NULL,
    execution_mode VARCHAR(32) NOT NULL,
    goal VARCHAR(2000) NOT NULL,
    status VARCHAR(32) NOT NULL,
    current_step INTEGER NOT NULL,
    max_steps INTEGER NOT NULL,
    max_cost_usd DECIMAL(18, 6),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_agent_run_agent
        FOREIGN KEY (agent_id) REFERENCES agent_definition(id)
);

CREATE INDEX idx_agent_run_agent_created
    ON agent_run(agent_id, created_at);
