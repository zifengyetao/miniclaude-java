# ADR-001：Harness-first Graph Runtime

- 状态：Accepted（第一阶段）
- 日期：2026-07-20
- 范围：`data-analyst`

## 背景

项目已有 `GraphSpec`、静态校验、Durable Run、Event 和 Checkpoint，但 `PilotScenarioService`
仍使用手写顺序执行节点。Graph 只是目录和展示资产，不是运行时事实源：

- 服务代码决定节点顺序；
- 高/低成本分支由 if/else 决定；
- Checkpoint 只记录步骤状态，没有明确 next-node cursor；
- 审批恢复依赖 `ANALYSIS_REQUEST` Artifact 重新调用后半段方法；
- 无法证明恢复没有重复已完成节点。

这会在资深 Agent 面试中被追问：Graph、Workflow 和业务服务谁才是控制面？

## 决策

引入 `application.platform.GraphRunner`：

1. `GraphSpec` 是节点顺序和条件分支的唯一事实源。
2. 业务 `NodeExecutor` 只执行单节点，不决定下一节点。
3. 每个节点完成后调用 DurableOrchestrator 原子记录 Run、Event 和 Checkpoint。
4. Checkpoint 保存：
   - `_graphName`
   - `_graphVersion`
   - `_completedNode`
   - `_nextNode`
   - `_attempt`
   - `_inputHash`
   - `_outputHash`
5. 恢复从最新 Checkpoint 的 `_nextNode` 开始，不重跑已完成节点。
6. 条件边只支持当前最小语义：`always` 或 `key=value`。
7. 多条边同时匹配时 fail-closed，不按声明顺序猜测。
8. Approval 节点通过单个 Durable 命令原子写 cursor、WAITING 状态和审批请求。
9. Terminal 节点通过单个 Durable 命令原子写 checkpoint 和 SUCCEEDED。

## 数据模型

```text
AgentRun
  id / tenant / status / currentStep / maxSteps / cost / timeout / version
    |
    +-- RunEvent（仅追加事实）
    |     sequence / type / idempotencyKey / payloadHash
    |
    +-- RunCheckpoint（可恢复快照）
    |     stepId / sequence / version / state / stateHash
    |     state:
    |       graphName / graphVersion
    |       completedNode / nextNode
    |       attempt / inputHash / outputHash
    |       business state
    |
    +-- ApprovalRequest
    |     stepId / actionType / actionHash / status / expiry
    |
    +-- Artifact
          type / name / contentHash / version
```

### Step 与 Attempt

- Step 是 Graph 中稳定的逻辑节点 ID，例如 `query`。
- Attempt 是该节点在同一 Run 中第几次形成已提交 Checkpoint。
- HTTP/消息重投但复用同一幂等键，不应增加逻辑 Attempt。
- 未来 Retry Policy 主动发起新尝试时才增加 Attempt。

## Analyst Graph

```text
sql-guard
  → metric
  → estimate
      ├─ approvalRequired=false → query
      └─ approvalRequired=true  → approval → query
  → verify
  → report
```

- Graph 版本从 `1.0.0` 升到 `1.1.0`。
- 低成本路径不产生 Approval 节点 Checkpoint。
- 高成本路径持久化 `approval → query` 游标后暂停。
- 恢复前由 DurableOrchestrator 校验有效批准。
- Query 前再次执行 SqlGuard，不信任等待期间保存的输入。

## 哈希语义

- Input/Output 使用 canonical JSON 后计算 SHA-256。
- Object Key 排序，Array 保持业务顺序。
- 哈希用于追踪和检测状态漂移，不用于加密、授权或数字签名。
- SQL 审批参数使用完整 SHA-256，不再使用 Java `String.hashCode()`。

## 失败与崩溃窗口

### NodeExecutor 执行前崩溃

没有新 Checkpoint；恢复仍从上一个 `_nextNode` 开始。

### NodeExecutor 成功、recordStep 前崩溃

节点会被重新执行。当前 analyst 工具为受控只读 Fake，因此可接受；生产写工具必须依赖
Tool Idempotency Key 和 Effect Ledger，GraphRunner 本身不能提供外部副作用 Exactly-once。

### recordStep 成功、awaitApproval 前崩溃

Local Runtime 使用 `recordStepAndAwaitApproval` 将 Checkpoint、WAITING 状态和 Approval 放在
同一数据库事务中；进程崩溃只会整体提交或整体回滚。Temporal 边界仍 fail-closed，等待 Activity 实现。

### awaitApproval 成功、响应丢失

相同审批幂等键重放不会创建第二条审批。

### Terminal checkpoint 成功、complete 前崩溃

Local Runtime 使用 `recordTerminalStep` 将 Terminal Checkpoint 与 SUCCEEDED 放在同一事务中，
不会留下 `_nextNode=null` 但 Run 仍为 RUNNING 的状态。

### Report 保存成功、Terminal 命令前崩溃

`GraphTerminalCommitter` 将 Report Artifact 与 Terminal Checkpoint/SUCCEEDED 放入同一数据库事务；
任一持久化失败时 Report 不可见。Artifact 同时使用稳定幂等键处理命令重放。

### 恢复请求重复

第一次恢复后 Run 不再是 `WAITING_APPROVAL`；第二次请求 fail-closed。后续可在 API 层增加
Command Idempotency Key，使重复请求返回第一次结果。

## 未选择的方案

### 在 ScenarioService 中继续手写流程

实现简单，但 Graph 不是事实源，无法支持版本、条件边、统一恢复和节点级 Eval。

### 立即切换 Temporal

Temporal 不能自动补齐业务幂等、Tool Receipt 和 Eval；当前 Worker/Activity 也未交付。
先在 Local Runtime 做实语义，再决定适配 Temporal。

### 在 AgentRun 表增加 current_node 字段

第一阶段不新增迁移。Cursor 保存在不可变 Checkpoint 中，足以演示恢复与审计。
未来 Worker 高频扫描需要结构化索引时，再新增专用 Execution 表，而不是反复解析 JSON。

## 结果

正向：

- Analyst 节点和条件分支由 Graph 真正驱动。
- 审批恢复不再依赖 Artifact 作为执行状态。
- 每个节点有版本、游标、Attempt 和输入输出证据。
- 可通过 Checkpoint 序列证明低成本跳过审批、高成本恢复不重跑前置节点。
- Approval/Terminal 持久边界已原子化，Report Artifact 与 SUCCEEDED 原子发布且可幂等重放。

代价：

- 当前 Runner 是同步、单进程调用模型。
- 条件表达式能力刻意受限。
- 外部 Tool 副作用仍缺少事务信封和 Effect Ledger。
- 尚无 Worker Lease、Heartbeat、自动接管和定时唤醒。

## 面试回答框架

被问“为什么不直接使用 DAG 引擎或 LangGraph”时：

1. 先说明控制面不变量与供应商无关领域模型。
2. Graph 负责拓扑，DurableOrchestrator 负责状态事务，NodeExecutor 负责业务能力。
3. Checkpoint 保存 next-node cursor，而不是依赖进程调用栈。
4. 模型和工具属于非确定性 Activity，不能放进确定性 Replay 逻辑。
5. Exactly-once 不是 Graph Runner 能保证的，必须通过业务幂等和 Effect Ledger实现。
6. 当前实现明确保留同步、无 Worker 的边界，不冒充生产级 Durable Runtime。
