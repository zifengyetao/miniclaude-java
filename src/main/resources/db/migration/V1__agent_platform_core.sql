-- 数字员工定义的版本化元数据。V1 只保存平台检索和启动 Run 所需字段；
-- Prompt、Rule、Skill 等不可变资产在后续治理迁移中独立存储，避免大字段耦合。
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

-- 同名定义可以存在多个语义化版本，但同一 name/version 组合必须唯一，
-- 保证 ReleaseManifest 能精确 pin 到可复现资产，而不是解析模糊的 latest。
CREATE UNIQUE INDEX uk_agent_definition_name_version
    ON agent_definition(name, version);

-- Agent Run 是持久编排的聚合根。这里只保存当前快照；完整状态迁移历史由
-- V2 的 append-only run_event 承担，避免更新快照时丢失审计轨迹。
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

-- 支持按数字员工倒序查看历史运行，是工作台和运营查询的主要访问路径。
CREATE INDEX idx_agent_run_agent_created
    ON agent_run(agent_id, created_at);
