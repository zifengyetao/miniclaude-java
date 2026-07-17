/** 定义应用外壳的静态导航项；菜单是否可见不代表用户拥有对应服务端权限。 */
import { Bot, Boxes, Braces, CheckSquare, GitFork, LayoutDashboard, MessageSquare, Settings, ShieldCheck, Sparkles, type LucideIcon } from 'lucide-react'

export interface NavItem { label: string; path: string; icon: LucideIcon }
export const navigation: NavItem[] = [
  { label: '工作台', path: '/', icon: LayoutDashboard },
  { label: 'Chat', path: '/chat', icon: MessageSquare },
  { label: '数字员工', path: '/employees', icon: Bot },
  { label: '智能体广场', path: '/agents', icon: Boxes },
  { label: 'Coding Agent', path: '/coding', icon: Braces },
  { label: '任务中心', path: '/tasks', icon: CheckSquare },
  { label: '审批中心', path: '/approvals', icon: ShieldCheck },
  { label: 'Graph Studio', path: '/graph', icon: GitFork },
  { label: '进化中心', path: '/evolution', icon: Sparkles },
  { label: '平台管理', path: '/admin', icon: Settings },
]
