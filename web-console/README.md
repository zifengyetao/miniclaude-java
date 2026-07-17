# MiniClaude Web Console

React + TypeScript + Vite 桌面工作台，直接对接 MiniClaude `/api/v1` 后端契约。

## 开发

```bash
npm install
npm run dev
```

Vite 会把 `/api` 代理到 `http://localhost:8080`。如需使用其他地址，可设置 `VITE_API_BASE_URL`（默认 `/api/v1`）。

平台 API Key 不读取构建环境变量。请点击左侧“API 凭据”，手工输入后保存到当前标签页的 `sessionStorage`；请求通过 `X-Platform-Api-Key` 发送。租户和操作者分别使用 `X-Tenant-Id`、`X-Actor-Id`。

## 验证

```bash
npm run lint
npm test
npm run build
npm audit --audit-level=high --registry=https://registry.npmjs.org
```

## 结构

- `src/app`：路由、导航与应用壳
- `src/features`：Chat、数字员工、运行、审批、Graph、Coding、进化、治理
- `src/pages`：工作台与智能体广场
- `src/shared/api`：实际 DTO 类型和接口客户端
- `src/shared/components`：统一 loading/error/empty 状态
