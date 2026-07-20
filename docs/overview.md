# 项目总览

## 一句话

私有化部署的 **企业 Agent 平台**（Java Spring Boot + React 工作台）：通用 Chat、数字员工、Coding / 数据分析 / 智能客服试点场景，以及风控调查 / 交易辅助监管仿真。默认 **建议型自治**——高风险与外部写操作必须人工审批，内置外部系统多为 Fake。

## 技术基线

| 项 | 现状 |
|----|------|
| JDK / Spring Boot | JDK 11 / Boot 2.7.18（升级 Boot 3 未做） |
| 后端分层 | `interfaces → application → domain ← infrastructure` |
| 编排 | 默认 `PLATFORM_ORCHESTRATOR=local`（JDBC 持久化）；Temporal 仅边界，非默认生产 |
| 前端 | `web-console/` React + TypeScript + Vite |
| 数据 | PostgreSQL（Compose/K8s）或本地 H2；Flyway V1–V5 |
| 语言 | 对用户回复中文 |

## 硬约束（不要违反）

1. **不要**在仓库/配置中写入真实 API Key；用环境变量 / `.env`（未提交）。
2. **不要**实现真实下单、真实 CRM 发送、对 `main/master` 写仓、force push。
3. 监管场景（风控/交易）：四眼审批、kill-switch、进化上限 L1；禁止自动不利客户处置。
4. 自进化 L0–L3；**L4 禁止**；候选须评测 → 评审 → 灰度 → 晋升。
5. Harness-first：确定性控制面（策略/审批/验证）包住概率模型。

## 两条主执行路径

```
路径 A — Chat
  ChatController → ChatApplicationService → AgentGateway → LegacyAgentRuntime → Agent 引擎（ReAct）

路径 B — 场景 / 数字员工 Run
  *ScenarioController → *ScenarioService → DurableOrchestrator → 步进 + 审批 + Artifact
  （RolePack + GraphSpec 定义节点；当前多为服务内线性步进，对齐 Graph）
```

## 场景 ID 速查

| ID | 名称 | 服务 |
|----|------|------|
| （chat） | 通用对话 | `ChatApplicationService` |
| `coding-agent` | Coding Agent | `PilotScenarioService` |
| `data-analyst` | 数据分析师 | `PilotScenarioService` |
| `customer-support` | 智能客服 | `PilotScenarioService` |
| `risk-investigation` | 风控调查 | `RegulatedScenarioService` |
| `trading-assistant` | 交易辅助 | `RegulatedScenarioService` |

详情见 [agent-flows.md](./agent-flows.md)。文件定位见 [code-map.md](./code-map.md)。
