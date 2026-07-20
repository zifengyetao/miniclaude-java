/**
 * 智能体广场：读取平台注册定义，并在浏览器内按名称、角色和描述过滤展示。
 *
 * 状态：
 * - agents：useAsync 加载全量 Agent 列表（无分页 API 时一次性拉取）
 * - query：本地搜索字符串，不参与 API 请求
 *
 * 安全：本地搜索与卡片可见性不代表调用权限；启动运行或场景仍由服务端鉴权。
 */
import { Bot, Search } from 'lucide-react'
import { useState } from 'react'
import { platformApi } from '../shared/api/client'
import { AsyncView } from '../shared/components/AsyncView'
import { useAsync } from '../shared/hooks/useAsync'

export function AgentSquarePage() {
  const agents = useAsync(platformApi.agents.list)
  const [query, setQuery] = useState('')
  // 客户端 filter：大小写不敏感子串匹配 name + roleName + description。
  const visible = agents.data?.filter((agent) => `${agent.name} ${agent.roleName} ${agent.description}`.toLowerCase().includes(query.toLowerCase())) ?? []
  return <div className="page"><div className="page-heading"><div><span className="eyebrow">Registry</span><h1>智能体广场</h1><p>浏览平台中注册的数字员工及其执行能力。</p></div></div><label className="filter-input"><Search size={16} /><input aria-label="搜索智能体" value={query} onChange={(e) => setQuery(e.target.value)} placeholder="搜索名称、角色或描述" /></label><AsyncView loading={agents.loading} error={agents.error} empty={visible.length === 0} emptyText="没有匹配的智能体" onRetry={agents.reload}><section className="card-grid">{visible.map((agent) => <article className="agent-card" key={agent.id}><header><span className="agent-icon large"><Bot size={22} /></span><span className="tag">{agent.status}</span></header><h2>{agent.name}</h2><small>{agent.roleName}</small><p>{agent.description}</p><div className="tags">{agent.executionModes.map((mode) => <span key={mode}>{mode}</span>)}</div><footer><span>{agent.riskLevel} 风险</span><strong>v{agent.version}</strong></footer></article>)}</section></AsyncView></div>
}
