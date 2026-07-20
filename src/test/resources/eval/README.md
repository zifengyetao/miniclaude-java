# Data Analyst Eval Dataset

`data-analyst-v1.jsonl` 是第一版固定评测集，共 30 条，用于验证 Graph 路径、安全阻断和审批边界。

## 字段

- `id`：稳定样本 ID。
- `category`：`safe-low-cost`、`safe-high-cost`、`unsafe-sql` 或 `invalid-input`。
- `input`：场景 API 输入。
- `expected.status`：启动请求完成后的 Run 状态。
- `expected.path`：预期 Graph 节点序列；失败样本只写成功进入的前缀。
- `expected.approval`：是否必须产生 `ANALYTICS_QUERY_COST` 审批。
- `expected.artifact`：预期终态 Artifact；无则为 `null`。
- `expected.reasonContains`：失败原因必须包含的稳定片段；非失败样本为 `null`。

## 版本规则

- 数据集内容变更必须新建版本，不原地改变既有样本语义。
- `DataAnalystEvalDatasetTest` 已实现确定性 Runner/Scorer；统计报告、LLM Judge 和发布门禁留到 Eval 阶段。
- 所有适配器仍是受控 Fake，不连接真实数仓。
