# 文档索引（给人类 & AI）

新会话或换模型时，**先读本目录，再按需打开代码**。不要默认全库扫描。

| 文档 | 何时读 | 内容 |
|------|--------|------|
| [overview.md](./overview.md) | 每次新会话 | 项目是什么、技术基线、硬约束、能力边界 |
| [architecture.md](./architecture.md) | 改平台/集成 | 分层、编排、Temporal/OPA/OTel 边界、Fake 适配器 |
| [agent-flows.md](./agent-flows.md) | 改业务 Agent | Chat / 数字员工 / Coding / 分析 / 客服 / 风控 / 交易链路 |
| [code-map.md](./code-map.md) | 要改代码时 | 包结构、关键类、API、Flyway、前端页面对应 |
| [security.md](./security.md) | 部署/安全 | 密钥、API Key、容器网络、功能边界 |
| [operations.md](./operations.md) | 运维 | Compose / K8s / 监控 / 升级回滚 |
| [interview-prep-overview.md](./interview-prep-overview.md) | Agent 面试冲刺 | 候选人基线、岗位定位、能力诊断与跨会话入口 |
| [interview-prep-progress.md](./interview-prep-progress.md) | 继续学习/改造 | 当前进度、已完成事项、下一步和决策记录 |

## 资深 Agent 面试冲刺

- [八周路线](./interview-prep-roadmap.md)
- [96 题题库](./interview-prep-question-bank.md)
- 标准参考答案：[A～C（1～30）](./interview-prep-answers-a-c.md) ·
  [D～F（31～62）](./interview-prep-answers-d-f.md) ·
  [G～I（63～96）](./interview-prep-answers-g-i.md)
- [项目审查](./interview-prep-project-review.md)
- [岗位市场](./interview-prep-market.md)
- [ADR-001 Harness-first Graph Runtime](./adr-001-harness-first-graph-runtime.md)
- [ADR-002 Shared Agent Harness](./adr-002-shared-agent-harness.md)

## 推荐阅读顺序（AI）

1. `overview.md`（约 1–2 分钟上下文）
2. 任务相关的一篇：场景 → `agent-flows.md`；架构 → `architecture.md`；定位文件 → `code-map.md`
3. 仅打开 `code-map.md` 列出的目标包/文件，再搜索

## 维护约定

- 新增场景 / 改 Graph / 改安全边界时，同步更新 `agent-flows.md` 与 `code-map.md`。
- 文档描述以仓库**当前实现**为准；计划文件 `.cursor/plans/` 仅作历史蓝图，不代替 docs。
