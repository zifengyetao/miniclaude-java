/**
 * 应用路由入口：把各功能页面挂载到统一外壳（AppShell）的工作区中。
 *
 * 路由表（path → 页面）：
 * - /           DashboardPage      平台工作台概览
 * - /chat       ChatPage           对话与会话
 * - /employees  EmployeesPage      数字员工定义管理
 * - /agents     AgentSquarePage    智能体广场浏览
 * - /coding     CodingPage         Coding Agent 场景
 * - /tasks      TasksPage          持久化运行任务中心
 * - /approvals  ApprovalsPage      人工审批
 * - /graph      GraphStudioPage    Graph 定义校验
 * - /evolution  EvolutionPage      受治理进化
 * - /admin      AdminPage          平台治理与审计
 *
 * 安全边界：路由仅控制客户端展示与导航，不构成任何功能或数据访问权限；
 * 用户可直接修改 URL 访问任意页面，服务端仍须对每个 API 独立鉴权。
 */
import { BrowserRouter, Route, Routes } from 'react-router-dom'
import { AdminPage } from '../features/admin/AdminPage'
import { ApprovalsPage } from '../features/approvals/ApprovalsPage'
import { ChatPage } from '../features/chat/ChatPage'
import { CodingPage } from '../features/coding/CodingPage'
import { EmployeesPage } from '../features/employees/EmployeesPage'
import { EvolutionPage } from '../features/evolution/EvolutionPage'
import { GraphStudioPage } from '../features/graph/GraphStudioPage'
import { TasksPage } from '../features/runs/TasksPage'
import { AgentSquarePage } from '../pages/AgentSquarePage'
import { DashboardPage } from '../pages/DashboardPage'
import { AppShell } from './AppShell'

/** 根组件：BrowserRouter 包裹嵌套路由，AppShell 提供侧栏与 Outlet 工作区。 */
export default function App() {
  return <BrowserRouter><Routes><Route element={<AppShell />}><Route index element={<DashboardPage />} /><Route path="chat" element={<ChatPage />} /><Route path="employees" element={<EmployeesPage />} /><Route path="agents" element={<AgentSquarePage />} /><Route path="coding" element={<CodingPage />} /><Route path="tasks" element={<TasksPage />} /><Route path="approvals" element={<ApprovalsPage />} /><Route path="graph" element={<GraphStudioPage />} /><Route path="evolution" element={<EvolutionPage />} /><Route path="admin" element={<AdminPage />} /></Route></Routes></BrowserRouter>
}
