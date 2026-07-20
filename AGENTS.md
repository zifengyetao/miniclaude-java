# Agent 指引（Cursor / Claude / 其他编程助手）

本仓库是企业 Agent 平台原型。**不要默认全库扫描。**

## 必读（按顺序）

1. [docs/overview.md](docs/overview.md) — 总览与硬约束  
2. 按任务选读：  
   - 业务链路 → [docs/agent-flows.md](docs/agent-flows.md)  
   - 架构/集成 → [docs/architecture.md](docs/architecture.md)  
   - 定位代码 → [docs/code-map.md](docs/code-map.md)  
3. 部署安全 → [docs/security.md](docs/security.md)、[docs/operations.md](docs/operations.md)

完整索引：[docs/README.md](docs/README.md)

## 工作方式

- 先读 docs，再只打开 `code-map` 指向的包/文件。
- 改场景 Graph 或安全边界时，同步更新 `docs/agent-flows.md` 与 `docs/code-map.md`。
- 对用户使用简体中文；勿提交真实密钥；勿实现真实下单/CRM 发送。
- 计划蓝图在 `.cursor/plans/`，以 `docs/` 当前实现描述为准。
