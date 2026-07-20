-- Graph/Tool 重试时，稳定幂等键确保同一逻辑制品只保存一次。
-- 保留 nullable 以兼容旧的“每次 save 都是新审计记录”语义；只有 saveIdempotent 使用该列。
ALTER TABLE scenario_artifact ADD COLUMN idempotency_key VARCHAR(240);

CREATE UNIQUE INDEX uk_scenario_artifact_idempotency
    ON scenario_artifact(tenant_id, run_id, idempotency_key);
