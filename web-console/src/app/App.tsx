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

export default function App() {
  return <BrowserRouter><Routes><Route element={<AppShell />}><Route index element={<DashboardPage />} /><Route path="chat" element={<ChatPage />} /><Route path="employees" element={<EmployeesPage />} /><Route path="agents" element={<AgentSquarePage />} /><Route path="coding" element={<CodingPage />} /><Route path="tasks" element={<TasksPage />} /><Route path="approvals" element={<ApprovalsPage />} /><Route path="graph" element={<GraphStudioPage />} /><Route path="evolution" element={<EvolutionPage />} /><Route path="admin" element={<AdminPage />} /></Route></Routes></BrowserRouter>
}
