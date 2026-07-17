-- 场景制品保存补丁/报告/案例包/草稿及安全阻断证据；记录存在不代表外部动作已执行。
-- content_hash 将审批绑定到确切内容，tenant_id + run_id 则构成应用层租户隔离查询键。
CREATE TABLE scenario_artifact (
    id VARCHAR(36) PRIMARY KEY,
    tenant_id VARCHAR(120) NOT NULL,
    run_id VARCHAR(36) NOT NULL,
    artifact_type VARCHAR(80) NOT NULL,
    name VARCHAR(500) NOT NULL,
    content TEXT NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL,
    -- why：制品必须归属于真实运行，禁止产生无法追溯生命周期的孤立审批材料。
    CONSTRAINT fk_scenario_artifact_run FOREIGN KEY (run_id) REFERENCES agent_run(id)
);

-- 支持按租户和运行顺序读取完整证据链，避免只用 run_id 进行跨租户扫描。
CREATE INDEX idx_scenario_artifact_run
    ON scenario_artifact(tenant_id, run_id, created_at);
