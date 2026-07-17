# 安全基线

## 凭据

- 仓库、镜像和 manifests 不包含真实 secret；`.env.example` 与 `secret.example.yaml` 仅提供占位符。
- Compose 启动前必须在未提交的 `.env` 中设置强随机 `POSTGRES_PASSWORD` 和 `PLATFORM_API_KEY`。
- Kubernetes 生产环境应使用 External Secrets、Sealed Secrets 或平台密钥管理系统，不要提交复制后的 Secret。
- 模型 API Key 应限制额度、定期轮换，并与开发环境隔离。

## API 边界

配置 `PLATFORM_API_KEY` 后，所有 `/api/**` 请求必须携带 `X-Platform-Api-Key`。Web 工作台只把用户手工输入的 Key 保存在当前标签页 `sessionStorage`，不会把 Key 编译进前端。该机制是最小部署边界，不替代企业 SSO、细粒度 RBAC、租户鉴权和密钥轮换。

生产入口必须启用 TLS，限制来源，配置 WAF/速率限制，并避免直接暴露 backend 和 PostgreSQL Service。

## 容器与网络

交付容器以非 root 用户运行，禁用提权并丢弃 Linux capabilities；Kubernetes 配置只读根文件系统、资源限制、探针和默认拒绝 NetworkPolicy。落地前需确认集群 CNI 实际执行 NetworkPolicy，并按模型供应商、镜像仓库、DNS 和观测端点收紧 egress。

工作区挂载可能包含敏感源码。只挂载专用目录，保持 `PLATFORM_ALLOWED_WORKSPACES` 最小化，不要挂载宿主机根目录、Docker socket、SSH 目录或云凭据。

## 功能边界

内置 Git、数据库、CRM、知识库、市场、OMS 等适配器均为 fake。系统不会真实下单；交易场景只生成草稿。引入生产适配器前必须完成威胁建模、最小权限、审批、审计、幂等和故障演练。
