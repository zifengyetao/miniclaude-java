/**
 * Web Console 浏览器入口（Vite + React 18）。
 *
 * 职责：
 * - 将 React 应用挂载到 index.html 中的 #root 节点
 * - 在开发模式下通过 StrictMode 触发双重渲染，帮助发现副作用与不安全生命周期用法
 *
 * 安全说明：本文件不涉及 API Key 或租户凭据；凭据由 AppShell 写入 sessionStorage，
 * 仅影响后续 fetch 请求头，不能替代服务端鉴权。
 */
import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import App from './app/App.tsx'

// 使用非空断言：index.html 保证存在 #root；若缺失则属于部署/构建错误，应尽早暴露。
createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
