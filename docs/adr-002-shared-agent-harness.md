# ADR-002：共享 Agent Harness 与受控自我升级

- 状态：Accepted
- 日期：2026-07-20
- 范围：Data Analyst、Customer Support、Coding Agent

## 背景

项目当前有两类执行方式：

- `LegacyAgentRuntime` 包装旧 `Agent` 大类，内部已经包含模型/工具循环、压缩、Memory、预算和权限，
  但这些能力埋在基础设施单体中，平台无法统一治理、观测和评测。
- Scenario Service 以手写步骤或 Graph 执行业务流程，确定性强，但不能表达 Coding/Research 类任务的
  动态规划、工具反馈和长时间自主迭代。

Graph 适合显式 Workflow，不应成为 Agent 平台核心。平台核心应是共享 Harness。

## 决策

构建一个共享 Agent Harness：

```text
Harness
  ├─ Role Profile
  ├─ Context Assembler / Compaction
  ├─ Model Turn
  ├─ Tool Policy / Permission / Effect
  ├─ Observation
  ├─ Verifier / Stop Controller
  ├─ Event / Trace / Eval Hooks
  └─ Session / Resume
```

标准 Loop：

```text
Goal
  → Assemble Context
  → Model Turn
  → Final Answer? ──yes──→ Verify → Complete/Continue
  → Tool Calls
  → Profile Allowlist
  → Profile Parameter/Order Guard
  → Policy Decision
  → Execute / Await Approval / Deny
  → Append Observation
  → Compact if needed
  → Next Turn
```

Graph 是可选执行策略：

- Coding、Research、通用 Chat：动态 Agent Loop。
- Data Analyst、Customer Support：默认 Agent Loop，关键安全节点由确定性工具和 Policy 保证。
- 监管、高风险审批、固定发布链路：Workflow/Graph。

## 一个 Harness，三个 Profile

### Data Analyst

- 允许：指标检索、只读 SQL Guard、成本估算、只读查询、引用验证、报告草稿。
- 禁止：DML/DDL、无 LIMIT 查询、生产写库、绕过成本审批。
- 完成条件：结果有引用、行数受限、报告通过 Deterministic Verifier。

### Customer Support

- 允许：PII Mask、知识检索、合规检查、回复草稿、人工转交。
- 禁止：CRM 自动发送、保留原始 PII、自动处理敏感投诉。
- 完成条件：草稿未发送；低置信或敏感意图进入 HITL。

### Coding Agent

- 允许：仓库搜索、文件读取、隔离 Worktree、Patch Proposal、Build/Test、独立 Review、PR Draft。
- 禁止：Main/Master 写入、Force Push、`--no-verify`、生产部署、秘密读取。
- 完成条件：Patch、测试证据和独立 Review 齐全；默认不 Push。

Profile 只声明能力和不变量，不复制三套 Loop。

`ProfileHarnessControls` 在模型声明完成时执行确定性终止验证，并在 Tool 前检查参数与调用顺序；
租户 `PolicyEngine` 仍是第二道授权 Gate。模型路由首次注册后不可覆盖，保证一次运行不会观察到热替换。

## Harness 领域对象

```text
HarnessRequest
  context / profile / model / goal / limits

HarnessState
  turn / toolCalls / transcript / tokenUsage / cost / status

ModelTurn
  text / toolCalls / finishReason / tokenUsage

ToolCall
  callId / toolName / arguments

Observation
  callId / outcome / output / receipt

HarnessEvent
  RUN_STARTED / CONTEXT_ASSEMBLED / MODEL_COMPLETED
  TOOL_REQUESTED / TOOL_ALLOWED / TOOL_DENIED / TOOL_COMPLETED
  COMPACTED / WAITING_APPROVAL / VERIFIED / RUN_COMPLETED / RUN_FAILED
```

不保存或暴露模型内部思维链。
运行时异常只对外输出稳定错误码，不把供应商错误、Prompt、SQL、路径或密钥写入 Event。

## 受控自我升级

“自我升级”不等于运行中的 Agent 修改自身代码、权限或生产 Prompt。

允许链路：

```text
Harness 失败轨迹/人工反馈
  → L0 Observation
  → Evolver 生成 Prompt/Skill/Model Routing 候选
  → 固定 Dataset + Regression + Hidden Holdout
  → Safety Gate
  → 人工 Review（L1/L2）
  → Shadow
  → Canary
  → Promote / Rollback
```

硬边界：

- L4 禁止。
- 监管场景最高 L1。
- Rule、Permission、Invariant 不允许 L3 自动晋升。
- L3 只允许 Allowlist 内、低风险、非监管的 Prompt/Skill Content。
- Evolver 只能提出 Patch，不能访问 Registry 写接口和 Hidden Holdout。
- Harness 每次运行固定解析精确 Asset Version；运行中不能热替换。

## 与现有代码的关系

- `LegacyAgentRuntime`：短期作为旧 Harness 适配器，不再继续扩展其单体职责。
- `infrastructure.engine.Agent`：保留为能力参考和兼容实现，逐步把控制面抽到共享 Harness。
- `GraphRunner`：冻结为 Workflow Strategy，仅服务需要显式拓扑的场景。
- `ControlledModelGateway` / `ControlledToolGateway`：作为共享 Harness 的模型和工具路由边界。
- `PolicyEngine`：每次工具调用前的强制 Gate。
- `GovernedEvolutionService`：消费 Harness Observation，负责候选状态机；不进入运行时 Loop。

## 分阶段交付

### Phase H1：可测试 Harness Core

- 供应商无关的 Model Turn、Tool Call、Observation、Event。
- Profile Allowlist、Max Turns、Max Tool Calls、Context Budget。
- Policy ALLOW/DENY/REQUIRE_APPROVAL。
- Scripted Model + Fake Tool 的完整 Loop 测试。

### Phase H2：三类 Profile 与现有端口适配

- 将 ScenarioPorts 适配成 Harness Tools。
- 保持全部外部写操作 Fake。
- Data/Support/Coding 使用同一 Loop，通过 Profile 区分。

当前已完成 Tool 注册与 Shadow 应用入口：

- Model/Tool 路由首次注册后不可覆盖。
- SQL 行数上限来自服务端配置，模型只能请求更小上限。
- Coding Workspace 固定来自 `ExecutionContext`，模型不能选择其他允许目录。
- 未配置生产模型 Turn Adapter，也未替换现有 REST 主链。

### Phase H3：Context、Streaming、Resume

- 分层 Context、Token Budget、压缩、来源与权限。
- 统一 `AgentEvent` 流、SSE 续传、取消。
- 持久 State、工具 Receipt、Effect Ledger 和 UNKNOWN。

### Phase H4：Eval 与进化闭环

- 轨迹 Runner、Deterministic Scorer、LLM Judge、人评抽检。
- Harness Failure → L0 Observation。
- 候选版本绑定 Dataset/Manifest，真实执行后进入 Gate。

## 不选择的方案

### 为三个场景复制三个 Agent Loop

会导致 Context、权限、工具、事件、预算和 Eval 语义漂移，无法形成平台能力。

### 继续扩展 Graph DSL 覆盖所有 Agent

动态 Coding/Research 任务无法预定义完整路径，复杂 Graph 会退化为另一种编程语言。

### 让 Agent 直接修改自身 Prompt 或 Skill

缺少独立评测、职责分离和回滚，属于被禁止的无治理自修改。

## 面试表述

> 我们把 Harness 作为平台核心：统一管理 Context、Model、Tool、Policy、Budget、Trace 和 Eval。
> Data、Support、Coding 只是不同 Profile。Graph 仅用于审批和监管等显式 Workflow。
> 自我升级不是在线自改，而是从失败轨迹生成版本化候选，经评测、人工、Shadow/Canary 后晋升。
