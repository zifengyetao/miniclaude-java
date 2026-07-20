# 架构与集成边界

> 总览见 [overview.md](./overview.md)；Agent 链路见 [agent-flows.md](./agent-flows.md)；包与 API 见 [code-map.md](./code-map.md)。

## 运行时

系统按 `interfaces → application → domain ← infrastructure` 分层。Spring Boot 后端提供 `/api/v1`，React 工作台通过同源 nginx 的 `/api` 反向代理访问后端；PostgreSQL 保存平台、治理、审批、场景工件和 durable run 数据。

默认 `PLATFORM_ORCHESTRATOR=local`，由 `LocalDurableOrchestrator` 和 JDBC store 提供本地持久化执行、检查点、暂停、恢复与审批状态。这是当前私有化交付的可用基线，不依赖外部工作流服务。

## Shared Agent Harness

平台核心方向是 Harness-first，而不是 Graph-first：

- `DefaultAgentHarness` 实现共享动态 Loop：Context → Model Turn → Tool Policy → Observation → Stop。
- `HarnessProfileCatalog` 为 Data Analyst、Customer Support、Coding Agent 声明不同工具 Allowlist、
  自治级别和预算；三者不复制 Loop。
- `ControlledHarnessModelGateway`、`ControlledToolGateway` 默认拒绝未注册模型和工具。
- `PolicyEngine` 在每次 Tool 执行前强制决策；`REQUIRE_APPROVAL` 停在 Harness 边界，不自动执行。
- `HarnessEventSink` 输出结构化事件，不记录思维链和完整 Tool Output。
- `GovernedHarnessObservationSink` 只把失败转为 L0 Observation，不能直接提案或发布。

当前交付：

- Phase H1 Core：Loop、Profile、完成验证、参数/顺序 Guard、Policy、事件和受控观察已经可测试。
- Phase H2 部分完成：现有 ScenarioPorts 已注册成 Data/Support/Coding 安全工具，并提供
  `HarnessScenarioService` Shadow 入口。
- 场景 REST 主链仍走现有 Scenario Service/Legacy Runtime；尚缺生产模型 Turn Adapter、
  Policy 参数规则和 Shadow Eval 后才允许灰度切流。

`GraphRunner` 仅作为显式 Workflow Strategy：适合审批、监管和固定发布链路，不作为 Coding/Research
类动态 Agent 的核心抽象。

## Temporal 边界

`DurableOrchestrator` 是供应商无关端口。只有显式设置 `PLATFORM_ORCHESTRATOR=temporal` 时才创建 Temporal 客户端。当前仓库定义了客户端与 workflow 边界，但没有交付完整 Worker、Activity 注册、生产重试/版本策略，因此 Docker Compose 和 Kubernetes 默认均不启用 Temporal，不能把该模式视为生产可用。

## 外部适配器

当前内置实现均用于受控演示与确定性验证：

- Git/工作区、构建检查和代码评审：fake，不执行 Git、shell 或远程仓库写入。
- 数据库分析：fake，只校验只读 SQL 并返回确定性结果，不连接业务数据库。
- CRM/知识库：fake，不连接客户系统或生产知识库。
- 市场、持仓、风控、图谱和案例库：fake，只生成模拟证据。
- OMS：只生成 `OMS_ORDER_DRAFT` 工件，`submitted=false`、`placeOrderCalled=false`，无真实下单能力。

生产接入必须在基础设施层实现 `ScenarioPorts` / `RegulatedScenarioPorts` 对应 SPI，配置独立凭据、网络策略、超时、幂等、审计和人工审批，并通过变更评审替换 fake bean。

## 可选能力

- OPA：领域层存在外部策略适配边界，但默认使用确定性本地策略引擎；仓库未打包 OPA 服务或生产 bundle。
- OpenTelemetry：当前未完成 SDK/Collector 接线；可在部署侧增加 Collector 和 Java Agent，经验证后启用。
- Temporal、OPA、OTel 都不是默认拓扑的一部分，部署资产不会假装它们可用。
