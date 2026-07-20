# Agent 执行链路

状态共性：`PENDING → RUNNING → WAITING_APPROVAL? → SUCCEEDED | FAILED | CANCELLED`。  
步进：`recordStep` 事件 + 可选 checkpoint + artifact + audit/metrics。

---

## 1. Chat

- **入口**：`POST /api/v1/chat`
- **链路**：Session 恢复/创建 → `ExecutionContext`（workspace/tenant/session/run/trace）→ `AgentGateway.chat` → `LegacyAgentRuntime` → 多轮 LLM + 工具，直到无 `tool_use`
- **特点**：非固定 Graph；默认权限 `default`；危险工具 fail-closed；不承载金融写操作

---

## 2. 数字员工（生命周期，非独立引擎）

```
创建 AgentDefinition → Registry pin → ReleaseManifest
  → 广场安装 → 启动场景 Run → Graph/服务步进
  → Evolution 候选（L0–L3）→ 评审/灰度 → 新不可变版本
```

- **入口**：`/api/v1/platform/agents`、治理与进化 API
- **本质**：可版本化 RolePack 容器；真正干活仍走场景 Run

---

## 3. Coding Agent（`coding-agent`）

Graph：`explore → plan → lease → patch → verify → review → pr-draft`

| 节点 | 行为 |
|------|------|
| explore | 只读探索 |
| plan | Plan-and-Execute |
| lease | `WorkspaceLease` 隔离 |
| patch | **仅提案**补丁 |
| verify | 构建/测试；失败则 FAILED |
| review | 独立评审；拒绝则 FAILED |
| pr-draft | 工件 `PATCH_PROPOSAL` / `PR_DRAFT` |

**禁止**：写 main/master；`--no-verify`；force push；production deploy。  
**终点**：不应用补丁、不创建外部 PR。

---

## 4. 数据分析师（`data-analyst`）

Graph：`sql-guard → metric → estimate → [approval] → query → verify → report`

- `SqlGuard`：只读、限制、禁危险语句；恢复后**再校验**
- 成本超阈值 → `awaitApproval`（`ANALYTICS_QUERY_COST`）→ `continueRun` 后才 query
- 终点：`REPORT` 工件；Fake 适配器，不连真实数仓

---

## 5. 智能客服（`customer-support`）

Graph：`pii-mask → retrieve → compliance → draft → confidence → [handoff] → draft-artifact`

- 原始 PII 不进检索/工件
- 产物永远 `sent=false`（无 CRM 发送）
- 敏感意图或置信度 &lt; 0.7 → `HUMAN_SUPPORT_HANDOFF` 审批暂停

---

## 6. 风控调查（`risk-investigation`）

Graph：`domain-policy → pii-mask → rule-score → model-score → evidence → case-package → independent-verifier → four-eyes → recommendation`

- 建议仅 `REVIEW` / `ESCALATE`；禁止自动 reject/ban/freeze
- 独立 Verifier + **四眼**（2 条审批、职责分离）
- kill-switch / deadline；进化上限 L1；仿真域 Fake

---

## 7. 交易辅助（`trading-assistant`）

Graph：`domain-policy → market-read → research-read → position-read → proposal → pre-trade-risk → four-eyes → oms-draft`

- 只读行情/研报/持仓（Fake）
- **确定性 pre-trade** 在审批前；人工不能覆盖硬限额
- 终点：`OMS_ORDER_DRAFT`，`submitted=false`，无 `placeOrder`

---

## 8. 入口对照

| 角色 | 主要 API | 终点 | 人审 |
|------|----------|------|------|
| Chat | `POST /api/v1/chat` | 文本 | 工具确认 |
| 数字员工 | `/api/v1/platform/agents` | 版本资产 | 发布/权限 |
| Coding | `/api/v1/scenarios/...` coding | Patch/PR Draft | 验证失败即停 |
| 分析 | scenarios data-analyst | Report | 高成本 |
| 客服 | scenarios customer-support | Reply Draft | 敏感/低置信 |
| 风控 | scenarios risk-investigation | Case Package | 四眼 |
| 交易 | scenarios trading-assistant | Proposal + OMS Draft | 四眼 + 硬风控 |

实现类：`PilotScenarioService`、`RegulatedScenarioService`、`ScenarioCatalog`、`RegulatedScenarioCatalog`。
