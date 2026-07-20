# 面试项目审查与改造优先级

## 1. 总体判断

当前面试含金量约为 **6.5/10**：

- 明显高于普通“LLM + Tool + UI”项目。
- 可以支撑资深 Java Agent 控制面、治理和安全设计故事。
- 暂不能声称是生产级 Agent Runtime、Coding Agent 平台或完整 Eval 平台。

面试叙事应从下面的问题开始：

> 为什么概率模型必须被确定性的状态、权限、预算、审批、验证和恢复机制包住？

不要从“五个业务场景和多个页面”开始。

## 2. 当前可讲的故事

### Durable 控制面

- Run、事件和 Checkpoint 有持久化语义。
- 存在幂等、乐观锁、预算、步数、墙钟和审批绑定的设计。
- 相关入口：
  - `infrastructure/durable/LocalDurableOrchestrator.java`
  - `infrastructure/durable/JdbcDurableStore.java`

### 高风险治理

- 四眼审批、职责分离、Kill-switch 和确定性硬限额。
- 监管场景只生成建议或草稿，不执行真实下单或不利客户处置。
- 相关入口：
  - `application/scenario/RegulatedScenarioService.java`
  - `RegulatedScenariosE2ETest.java`

### 平台化边界

- 领域端口隔离编排、运行时、工具和外部适配器。
- 默认 Local JDBC，Temporal/OPA/OTel 不冒充已生产化。
- 相关入口：
  - `docs/architecture.md`
  - `domain/durable/DurableOrchestrator.java`

### 安全契约测试

- 已有持久语义、审批、场景安全和治理测试。
- 2026-07-20 审查时执行 `mvn test`，41/41 通过。

## 3. 面试深挖的关键硬伤

### P0：Graph 只定义和校验，不负责执行

`GraphSpec` 和 `GraphValidator` 主要提供结构描述与静态校验；Pilot/Regulated 场景仍以手写顺序执行。

面试官可能追问：

- 条件边、循环、子图和动态 Fan-out 谁执行？
- 节点游标、Attempt、重试和节点级恢复如何表示？
- Graph 版本升级时进行中的 Run 如何处理？

### P0：Durable 尚未形成完整 Runtime

当前更接近请求内同步步进加状态持久化，缺少：

- Worker Lease 与 Heartbeat；
- 自动接管和定时唤醒；
- Step Attempt 与重试策略；
- 进程崩溃后从节点游标继续；
- Workflow 版本和确定性重放。

“数据库里还有状态”不等于“任务可以正确恢复”。

### P0：Tool 缺少事务语义

当前 Tool 模型缺少：

- READ/WRITE/IRREVERSIBLE Effect 分类；
- Caller-provided Idempotency Key；
- Deadline 与 Retry Policy；
- Receipt、查询确认和 Effect Ledger；
- `UNKNOWN` 状态；
- Prepare/Commit/Compensate；
- Approval Binding。

工具超时但外部副作用可能已成功时，不能直接重试。

### P0：Eval 接收分数，但不产生分数

当前治理服务主要接收外部提供的质量、安全、成本和延迟指标，并据此执行 Gate。尚缺：

- 版本化 Dataset；
- 实际任务 Runner；
- Deterministic Scorer 与 LLM Judge；
- 轨迹回放和失败分类；
- 统计置信度和 Baseline 对比；
- 真实 Shadow/Canary 流量与自动回滚。

### P1：Context Engineering 基本未闭环

- `ContextStore` 尚无生产实现。
- `ContextSnapshot` 语义过弱。
- 缺少 Token Budget、来源、时效、权限、PII、压缩和丢弃原因。
- Chat 仍依赖进程内 Legacy Session。

### P1：Coding Harness 仍是 Fake

Coding 场景没有真实执行 Git/Shell/Build，没有 Patch Apply、隔离 Worktree 和测试闭环，因此只能讲安全工作流设计，不能讲 Coding Agent 完成率。

### P1：MCP 未进入统一平台治理

旧引擎有 stdio MCP Client 和工具发现/调用，但没有统一进入平台 Tool Gateway 的租户、策略、审计和事务模型。

## 4. 前两周：只证明执行语义

收缩为 `data-analyst` 黄金链路：

1. 实现 `GraphRunner`，真正读取 `GraphSpec`。
2. 持久化 Node Cursor、Attempt、输入输出 Hash。
3. 增加单进程 Worker Lease/Heartbeat 与任务接管。
4. 使用进程终止或故障开关注入，验证崩溃恢复。
5. 定义 `ToolExecutionEnvelope` 和 Effect Ledger。
6. 接一个真实只读 PostgreSQL 或本地数据适配器。
7. 建立 100～200 条可复现 Eval 数据。
8. 统一关联 Trace、Run、Node、Tool、成本和延迟。
9. 使用 PostgreSQL Testcontainers 验证并发和恢复，不只依赖 H2 单 JVM 语义。

建议演示：

```text
歧义指标问题
  → 引用指标定义
  → 生成只读 SQL
  → 超阈值后持久暂停
  → 审批绑定 SQL Hash
  → Query 节点执行时故意终止进程
  → 新 Worker 从 Checkpoint 接管
  → 重复审批/恢复请求不产生重复查询和工件
  → 输出引用、Trace、成本、延迟和 Tool Receipt
  → 运行回归集并展示 Release Gate
```

## 5. 第三至四周：补平台差异化

1. Context Pipeline：
   - 静态指令、会话摘要、检索证据和工具结果分层；
   - Token Budget、来源、PII 和丢弃原因可观测。
2. MCP 统一治理：
   - Server/Tool Capability Registry；
   - Tenant Allowlist、超时、审计、Effect 分类；
   - 不追求全协议覆盖。
3. Eval：
   - Dataset + Deterministic Scorer + LLM Judge；
   - Baseline 对比、Bootstrap 置信区间和 Regression Gate。
4. Coding Harness：
   - 临时 Worktree；
   - Patch Apply；
   - 命令 Allowlist；
   - Build/Test/Review/Cleanup；
   - 不 Push、不写 Main/Master。
5. 故障矩阵：
   - 节点前/后崩溃；
   - 响应丢失；
   - 重复回调；
   - 审批过期；
   - 工具超时；
   - Worker 争抢。

## 6. 建议指标

以下是训练目标，不是当前事实。只有实际运行后才能写入简历：

| 维度 | 建议验收目标 |
|------|--------------|
| 恢复正确性 | 100 个故障注入点恢复成功率 100%，p95 接管时间小于 2 秒 |
| 副作用安全 | 10000 次重复/乱序请求，重复外部副作用为 0 |
| 策略安全 | 至少 200 条越权/注入/参数篡改用例，绕过数为 0 |
| 任务质量 | 至少 200 条固定集，Task Success 至少 80% |
| 引用 | Citation Precision 至少 95% |
| 合规 | Unsupported Claim 不超过 3%，PII 明文落盘为 0 |
| 效率 | 报告 p50/p95、Token、成本，并与 Baseline 比较 |
| Coding | 记录 Pass@1、Test Pass、Patch Acceptance 和人工介入率 |

## 7. 明确停止建设

- 不再增加新的业务场景。
- 不继续扩充 L2/L3 “自进化”页面和状态名。
- 不做真实交易、CRM 发送或生产写适配器。
- 不先堆 Temporal/OPA/OTel 名词，先把 Local Runtime 语义做实。
- 不继续增加模型供应商、多 Agent 角色或 UI 大屏。
- 不把 Spring Boot 升级当作 Agent 项目亮点。

## 8. 回答项目问题的边界

可以说：

- “这是 Harness-first 的企业 Agent 平台原型。”
- “实现了控制面、安全不变量和本地持久化基线。”
- “Temporal、真实外部适配器和完整 Eval Runner 尚未生产化。”

不可以说：

- “这是生产级 Durable Agent Runtime。”
- “已经实现 Exactly-once 工具执行。”
- “已经完成真实 Shadow/Canary。”
- “Coding Agent 已经能安全修改真实仓库。”

