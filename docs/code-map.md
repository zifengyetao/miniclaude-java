# 代码地图（按需打开，避免全库扫描）

根包：`com.miniclaude`

## 分层目录

```
src/main/java/com/miniclaude/
  interfaces/rest/          # REST 控制器
  application/              # 用例服务
    chat/                   # ChatApplicationService
    scenario/               # Pilot / Regulated 场景
    platform/               # 平台 Agent / Run / GraphRunner
    governance/             # 策略、审计、评测、进化
  domain/                   # 端口与模型（勿依赖 Spring）
    runtime/                # AgentRuntime、AgentHarness、ExecutionContext、网关端口
    durable/                # DurableOrchestrator、AgentRun、审批
    graph/                  # GraphSpec、验证器
    governance/             # Registry、Policy、Evolution 模型
  infrastructure/
    runtime/                # LegacyAgentRuntime
    durable/                # LocalDurableOrchestrator、JDBC store
    engine/                 # 旧 Agent 引擎核心（体积大，慎扫）
    temporal/               # Temporal 边界（非默认）
    scenario/               # Fake SPI 适配器
web-console/src/            # React 工作台
db/migration/               # Flyway（在 resources 下）
```

## Controllers → Service（改 API 从这里进）

| API 前缀 | Controller | 应用服务（典型） |
|----------|------------|------------------|
| `/api/v1/chat` | `ChatController` | `ChatApplicationService` |
| `/api/v1/sessions` | `SessionController` | session 仓储 |
| `/api/v1/platform/agents` | `PlatformAgentController` | platform / registry |
| `/api/v1/platform/runs` | `PlatformRunController` | durable run 暂停恢复取消审批 |
| `/api/v1/platform/graphs` | `GraphController` | Graph 校验/查询 |
| `/api/v1/scenarios` | `PilotScenarioController` | `PilotScenarioService` |
| `/api/v1/scenarios` | `RegulatedScenarioController` | `RegulatedScenarioService` |
| `/api/v1/governance` | `GovernanceController` | Registry / Policy / Audit / Eval |
| `/api/v1/governance/evolution` | `GovernedEvolutionController` | 受控进化 |
| `/health` 等 | `HealthController` | 健康检查 |

场景 ID 与 Graph 定义：

- `application/scenario/ScenarioCatalog.java` — coding / analyst / support
- `application/scenario/RegulatedScenarioCatalog.java` — risk / trading

## 关键端口（扩展集成时实现）

| 端口 | 用途 |
|------|------|
| `domain.runtime.AgentRuntime` | Chat/工具循环 |
| `domain.durable.DurableOrchestrator` | Run 生命周期 |
| `ScenarioPorts` | Coding / Analytics / Knowledge Fake→真 |
| `RegulatedScenarioPorts` | 风控/行情/持仓/OMS 草稿 |

默认编排实现：`infrastructure.durable.LocalDurableOrchestrator`。

Graph 执行：

- `application/platform/GraphRunner.java` — 解释 `GraphSpec` 节点/条件边，持久化
  next-node cursor、attempt、输入输出 hash，并从最新 checkpoint 恢复
- `application/platform/GraphTerminalCommitter.java` — 将终态数据库制品与
  Terminal checkpoint/SUCCEEDED 放在同一事务发布
- `application/scenario/PilotScenarioService.java` — 提供 data-analyst 节点执行器；
  Coding/Support 当前仍是服务内线性步进
- `application/platform/GraphRuntime.java` — 早期单确定性节点运行时，当前场景主链使用 `GraphRunner`
- `src/test/resources/eval/data-analyst-v1.jsonl` — 30 条 Analyst 固定评测集
- `DataAnalystEvalDatasetTest.java` — 状态、路径、审批、制品和失败原因的确定性 Scorer
- `docs/adr-001-harness-first-graph-runtime.md` — 数据模型、恢复语义、崩溃窗口与面试边界

Shared Harness：

- `domain/runtime/AgentHarness.java` — 共享 Loop 用例端口与结构化结果
- `domain/runtime/HarnessProfile.java` — Role 能力、自治模式和预算
- `domain/runtime/HarnessModelGateway.java` — 文本/Tool Call 模型 Turn 端口
- `domain/runtime/HarnessEventSink.java` — Trace/Eval/Evolution 事件出口
- `domain/runtime/ToolRegistry.java` — 不可覆盖的工具注册控制面端口
- `application/platform/DefaultAgentHarness.java` — Context/Model/Policy/Tool/Observation Loop
- `application/scenario/HarnessProfileCatalog.java` — Data/Support/Coding 三类 Profile
- `application/scenario/ProfileHarnessControls.java` — Tool 参数/顺序 Guard 与完成验证
- `application/scenario/HarnessScenarioToolRegistrar.java` — ScenarioPorts → 共享安全 Tools
- `application/scenario/HarnessScenarioService.java` — 三场景 Harness Shadow 应用入口
- `application/governance/GovernedHarnessObservationSink.java` — 失败轨迹 → L0 Observation
- `infrastructure/runtime/ControlledHarnessModelGateway.java` — 模型白名单路由
- `docs/adr-002-shared-agent-harness.md` — Harness-first 与受控自我升级决策

## Flyway

`src/main/resources/db/migration/`

| 版本 | 主题（概要） |
|------|----------------|
| V1 | 基础 session 等 |
| V2 | events / checkpoints / approvals |
| V3 | governance / registry |
| V4 | evolution |
| V5 | regulated 相关资产 |
| V6 | 场景 Artifact 幂等键 |

改表结构必须新迁移，勿改已发布脚本。

## 前端页面（`web-console/src`）

大致对应：Chat、数字员工、智能体广场、Coding、任务/审批、Graph、进化中心、Admin。  
联调时先搜页面组件里的 `fetch`/`api` 路径，再对后端 Controller。

## 大文件注意

`infrastructure/engine/` 与旧 `Agent` 引擎体积大。除非改 Chat 工具循环，否则优先读 `LegacyAgentRuntime` 与 `domain/runtime` 端口，不要整目录 dump。

## 配置与部署

- 应用配置：`src/main/resources/application.yml` + env（见 `.env.example`）
- Compose：仓库根 `docker-compose.yml`
- K8s：`deploy/k8s/`
- 安全/运维细节：`docs/security.md`、`docs/operations.md`
