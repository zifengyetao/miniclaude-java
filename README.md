# Mini Claude Code — Java（Spring Boot + DDD）

与 Python / TypeScript 版功能对齐的 Java 实现，现正演进为 **Spring Boot 企业 Agent 平台**，按 DDD 分层组织。当前兼容 **Java >= 11、Maven 3.6+**。

## 架构

```
interfaces/          # REST 控制器、DTO
application/         # 应用服务（用例编排）
domain/              # 领域模型与端口（AgentGateway、SessionRepository）
infrastructure/      # 适配器 + 原 Agent 引擎（engine/）
web-console/         # React + TypeScript 企业 Agent 工作台
```

## 快速开始

### Docker Compose（推荐私有化体验）

```bash
cp .env.example .env
# 设置强随机 POSTGRES_PASSWORD、PLATFORM_API_KEY；模型调用需要 MOONSHOT_API_KEY
docker compose config
docker compose up --build -d
```

访问 `http://127.0.0.1:8088`，在左侧“API 凭据”输入与 `.env` 相同的 `PLATFORM_API_KEY`。默认拓扑只有 PostgreSQL、backend 和 web；Temporal、OPA、OpenTelemetry 不会默认启动，也不应被视为已经可用。数据分别保存在 `postgres_data` 与 `workspace_data` 卷。

### 本地 Java

```bash
cp .env.example .env
./run.sh               # 启动服务 http://127.0.0.1:8080
```

或：

```bash
mvn -q -DskipTests package
set -a && source .env && set +a
java -jar target/miniclaude-java-1.0.0.jar
```

### API 示例

```bash
# 健康检查
curl -s http://127.0.0.1:8080/health

# 对话（自动建 session）
curl -s http://127.0.0.1:8080/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d '{"message":"用一句话介绍本项目"}'

# 续聊（带上返回的 sessionId）
curl -s http://127.0.0.1:8080/api/v1/chat \
  -H 'Content-Type: application/json' \
  -d '{"sessionId":"<id>","message":"再说详细一点"}'

# Session 列表
curl -s http://127.0.0.1:8080/api/v1/sessions

# 数字员工模板/定义
curl -s http://127.0.0.1:8080/api/v1/platform/agents

# 创建持久化 Run（将 <agentId> 替换为上一步返回的 id）
curl -s http://127.0.0.1:8080/api/v1/platform/runs \
  -H 'Content-Type: application/json' \
  -d '{"agentId":"<agentId>","executionMode":"PLAN_EXECUTE","goal":"分析任务并输出执行计划"}'
```

### 旧 CLI（可选）

```bash
./run.sh --cli --yolo "读一下 README"
```

### Web 工作台

后端启动后，在另一个终端运行：

```bash
cd web-console
npm ci --registry=https://registry.npmjs.org
npm run dev
```

Vite 开发服务器会把 `/api` 代理到 `http://127.0.0.1:8080`。生产镜像由非 root nginx 提供静态资源、SPA fallback，并把同源 `/api` 反代到 backend。API Key 不进入前端构建产物，只保存在当前标签页 `sessionStorage`。

### 配置

见 `src/main/resources/application.yml` 与环境变量：

| 配置 / 环境变量 | 说明 |
|-----------------|------|
| `server.port` | HTTP 端口，默认 8080 |
| `server.address` / `SERVER_ADDRESS` | 默认仅监听 `127.0.0.1` |
| `miniclaude.model` / `MINI_CLAUDE_MODEL` | 模型 |
| `miniclaude.permission-mode` | 默认 `default`；仅在受控本地环境显式启用 `bypassPermissions` |
| `MOONSHOT_API_KEY` | Kimi / Moonshot Key |
| `DATABASE_URL` | 默认使用 `./data/miniclaude` H2；生产建议 PostgreSQL JDBC URL |
| `DATABASE_USERNAME` / `DATABASE_PASSWORD` | PostgreSQL 用户名与密码 |
| `PLATFORM_API_KEY` | 配置后 `/api/**` 必须携带 `X-Platform-Api-Key`；未配置时仅允许本机请求 |
| `PLATFORM_ORCHESTRATOR` | 默认 `local`；`temporal` 只有边界实现，当前不作为完整生产方案 |
| `PLATFORM_ALLOWED_WORKSPACES` | Agent 可访问的工作区白名单，生产必须最小化 |

API Key 不再提供代码内默认值。历史上使用过仓库内明文 Key 的环境应先在模型供应商处轮换密钥。

### Kubernetes

基础 manifests 位于 `deploy/k8s`，包含 Namespace、ConfigMap、Secret 示例、PostgreSQL/Backend/Web、PVC、探针、资源限制、安全上下文和 NetworkPolicy。先通过企业 Secret 管理系统创建 `miniclaude-secrets`，再执行 `kubectl apply -k deploy/k8s`。镜像名、StorageClass、Ingress/TLS、备份与高可用策略必须按目标环境调整。

### 生产限制与外部适配器

当前默认 durable 执行使用本地 JDBC/PostgreSQL，不连接 Temporal。内置 Git/工作区、构建、数据库分析、CRM/知识库、市场、持仓、风控、图谱、案例库与 OMS 适配器均为确定性 fake：不执行真实 Git/SQL/CRM 调用，交易场景只产出 OMS 草稿，**没有真实下单能力**。生产接入必须另行实现 SPI，并补齐最小权限、审批、审计、超时、幂等和故障演练。

生产入口还需配置 TLS、企业 SSO/RBAC、限流、Secret 管理、镜像签名/扫描、数据库备份与高可用、日志脱敏和告警。基础 Compose 与 manifests 不是完整合规方案。

详细边界与手册：

- [架构与适配器边界](docs/architecture.md)
- [安全基线](docs/security.md)
- [部署与运维](docs/operations.md)

### Java / Spring Boot 升级说明

当前交付基线固定为 **JDK 11 + Spring Boot 2.7.18**。升级到 JDK 17/21 或 Spring Boot 3.x 应作为独立迁移，重点验证 Jakarta 命名空间、第三方依赖、Temporal SDK、序列化、Flyway、容器镜像和回归测试；不要在未验证时直接替换运行时基线。

### Kimi 联网搜索

使用 `MOONSHOT_API_KEY` 时，引擎按 [Kimi `$web_search`](https://platform.kimi.com/docs/guide/use-web-search) 注入内置工具；非 Moonshot 仍用本地 DuckDuckGo `web_search`。

## 测试

```bash
bash test/setup.sh
mvn -q -DskipTests package
javac -cp target/miniclaude-java-1.0.0.jar -d target/test-classes \
  src/test/java/com/miniclaude/infrastructure/engine/SmokeTest.java
java -cp target/test-classes:target/miniclaude-java-1.0.0.jar \
  com.miniclaude.infrastructure.engine.SmokeTest
bash test/cleanup.sh
```

## 模块对照

| 分层 | 说明 |
|------|------|
| `interfaces.rest` | Chat / Session / Health API |
| `application.*` | Chat / Session 用例 |
| `domain.agent` / `domain.session` | 端口与聚合 |
| `infrastructure.engine` | 原 Agent、Tools、Prompt 等 |
| `infrastructure.agent.EngineAgentGateway` | 实现 `AgentGateway` |
| `infrastructure.session.InMemorySessionRepository` | 内存 Session |
