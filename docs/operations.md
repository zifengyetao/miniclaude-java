# 部署与运维

## Docker Compose

```bash
cp .env.example .env
# 替换 .env 中所有 REPLACE_* 占位符
docker compose config
docker compose up --build -d
docker compose ps
```

工作台默认访问 `http://127.0.0.1:8088`。在“API 凭据”中输入与 `.env` 一致的 `PLATFORM_API_KEY`。停止服务使用 `docker compose down`；只有确认不再需要数据时才执行 `docker compose down -v`。

PostgreSQL 数据位于 `postgres_data`，Agent 工作区位于 `workspace_data`。备份应使用 `pg_dump` 并加密存储；恢复必须在隔离环境演练。升级前同时备份数据库与工作区。

## Kubernetes

先构建并推送两个内部镜像，将 `backend.yaml`、`web.yaml` 的占位镜像地址改为不可变 digest。Secret 示例不可直接用于生产：

```bash
kubectl apply -f deploy/k8s/namespace.yaml
cp deploy/k8s/secret.example.yaml /tmp/miniclaude-secret.yaml
# 在 /tmp 文件中替换占位符，或改用企业 Secret 管理方案
kubectl apply -f /tmp/miniclaude-secret.yaml
kubectl apply -k deploy/k8s
kubectl -n miniclaude rollout status deployment/postgres
kubectl -n miniclaude rollout status deployment/backend
kubectl -n miniclaude rollout status deployment/web
```

基础 Service 为 `ClusterIP`。生产入口需另配 Ingress/Gateway、TLS 证书、域名和组织认证。PVC 的 StorageClass、容量、备份、跨可用区策略及 PodDisruptionBudget 需按目标集群补齐。单副本 PostgreSQL 仅适合基础交付；生产建议使用托管 PostgreSQL 或带备份/故障转移的数据库 Operator。

## 监控与排障

- 存活与就绪：后端 `/actuator/health`，前端 `/healthz`。
- 后端默认暴露 health、info、metrics；暴露到监控系统前应增加认证和网络限制。
- 首查容器状态、探针、数据库连接和 Flyway 日志，再查请求关联信息与业务审计记录。
- 资源告警至少覆盖 CPU、内存、重启次数、HTTP 5xx、延迟、连接池、PostgreSQL 容量和备份结果。

Temporal、OPA、OpenTelemetry 未包含在默认拓扑。启用前应分别完成 Worker/Activity、策略 bundle/故障模式、Collector/采样与数据脱敏设计，并在独立环境验证。

## 升级与回滚

当前编译基线为 JDK 11、Spring Boot 2.7.18。升级 JDK 17/21 或 Spring Boot 3.x 前，需单独处理 Jakarta 命名空间、依赖兼容、镜像基线、序列化与数据库迁移；不要与普通应用发布混合。应用回滚不等于数据库回滚，Flyway 迁移需采用向前兼容和 expand/contract 策略。
