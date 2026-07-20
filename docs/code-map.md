# 代码地图（按需打开，避免全库扫描）

根包：`com.miniclaude`

## 分层目录

```
src/main/java/com/miniclaude/
  interfaces/rest/          # REST 控制器
  application/              # 用例服务
    chat/                   # ChatApplicationService
    scenario/               # Pilot / Regulated 场景
    platform/               # 平台 Agent / Run
    governance/             # 策略、审计、评测、进化
  domain/                   # 端口与模型（勿依赖 Spring）
    runtime/                # AgentRuntime、ExecutionContext、网关端口
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

## Flyway

`src/main/resources/db/migration/`

| 版本 | 主题（概要） |
|------|----------------|
| V1 | 基础 session 等 |
| V2 | events / checkpoints / approvals |
| V3 | governance / registry |
| V4 | evolution |
| V5 | regulated 相关资产 |

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
