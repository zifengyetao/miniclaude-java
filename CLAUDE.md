# Mini Claude Java — Agent 项目规则

@./.claude/rules/chinese-greeting.md

@./docs/overview.md

## 上下文策略（省 token）

新会话先读文档，再按需打开代码，**禁止无目标全库扫描**：

1. [docs/overview.md](docs/overview.md)
2. 场景/链路 → [docs/agent-flows.md](docs/agent-flows.md)
3. 定位文件 → [docs/code-map.md](docs/code-map.md)
4. 架构边界 → [docs/architecture.md](docs/architecture.md)

索引：[docs/README.md](docs/README.md) · 通用助手入口：[AGENTS.md](AGENTS.md)

## 硬约束摘要

- 私有化、建议型自治；高风险动作需人工审批
- 外部系统默认 Fake；无真实下单 / 无 CRM 自动发送
- 编排默认 local JDBC；Temporal/OPA/OTel 非默认拓扑
- JDK 11 + Spring Boot 2.7；改场景须同步更新 docs
