/**
 * 定义应用外壳侧栏的静态导航项。
 *
 * 说明：
 * - label/path/icon 仅用于 UI 渲染与 react-router NavLink 高亮
 * - 菜单是否可见不代表用户拥有对应服务端权限；隐藏菜单不能阻止直接访问 URL 或 API
 */
import { Bot, Boxes, Braces, CheckSquare, GitFork, LayoutDashboard, MessageSquare, Settings, ShieldCheck, Sparkles, type LucideIcon } from 'lucide-react'

/** 单个导航项的数据契约：展示文案、路由路径与 Lucide 图标组件。 */
export interface NavItem { label: string; path: string; icon: LucideIcon }

/**
 * 侧栏导航顺序与路由表（App.tsx）一一对应。
 * 调整顺序只影响 UI，不影响后端能力注册。
 */
export const navigation: NavItem[] = [
  { label: '工作台', path: '/', icon: LayoutDashboard },           // 概览指标与快捷入口
  { label: 'Chat', path: '/chat', icon: MessageSquare },           // 对话场景
  { label: '数字员工', path: '/employees', icon: Bot },            // Agent 定义 CRUD
  { label: '智能体广场', path: '/agents', icon: Boxes },           // 只读浏览注册表
  { label: 'Coding Agent', path: '/coding', icon: Braces },        // 编码场景运行时
  { label: '任务中心', path: '/tasks', icon: CheckSquare },        // 持久化 Run 控制
  { label: '审批中心', path: '/approvals', icon: ShieldCheck },    // 人工 in-the-loop
  { label: 'Graph Studio', path: '/graph', icon: GitFork },        // Graph JSON 校验
  { label: '进化中心', path: '/evolution', icon: Sparkles },       // 受治理演进
  { label: '平台管理', path: '/admin', icon: Settings },           // 资产/策略/审计
]
