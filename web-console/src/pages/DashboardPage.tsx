import { ArrowRight, Bot, CheckCircle2, Clock3, PlayCircle } from 'lucide-react'
import { Link } from 'react-router-dom'
import { governanceApi, platformApi } from '../shared/api/client'
import { AsyncView } from '../shared/components/AsyncView'
import { useAsync } from '../shared/hooks/useAsync'

export function DashboardPage() {
  const state = useAsync(async () => {
    const [agents, runs, assets] = await Promise.all([platformApi.agents.list(), platformApi.runs.list(), governanceApi.assets()])
    return { agents, runs, assets }
  })
  const active = state.data?.runs.filter((run) => ['RUNNING', 'PLANNING', 'VERIFYING'].includes(run.status)) ?? []
  const succeeded = state.data?.runs.filter((run) => run.status === 'SUCCEEDED').length ?? 0

  return <div className="page">
    <div className="page-heading"><div><span className="eyebrow">Overview</span><h1>平台工作台</h1><p>跨智能体查看运行、资产与治理状态。</p></div><Link className="button primary" to="/tasks">新建运行 <ArrowRight size={15} /></Link></div>
    <AsyncView loading={state.loading} error={state.error} onRetry={state.reload}>
      <section className="metrics">
        <article><span>数字员工</span><strong>{state.data?.agents.length ?? 0}</strong><small>{state.data?.agents.filter((agent) => agent.status === 'ACTIVE').length ?? 0} 个已激活</small></article>
        <article><span>运行中</span><strong>{active.length}</strong><small>共 {state.data?.runs.length ?? 0} 次运行</small></article>
        <article><span>成功运行</span><strong>{succeeded}</strong><small>持久化执行记录</small></article>
        <article><span>治理资产</span><strong>{state.data?.assets.length ?? 0}</strong><small>{state.data?.assets.filter((asset) => asset.status === 'PUBLISHED').length ?? 0} 个已发布</small></article>
      </section>
      <div className="dashboard-grid">
        <section className="panel"><header><div><h2>最近运行</h2><p>持久化运行的实时状态</p></div><Link to="/tasks">查看全部</Link></header><div className="rows">{state.data?.runs.slice(0, 6).map((run) => <article className="run-row" key={run.id}><span className={`status-icon ${run.status.toLowerCase()}`}>{run.status === 'SUCCEEDED' ? <CheckCircle2 size={17} /> : run.status === 'RUNNING' ? <PlayCircle size={17} /> : <Clock3 size={17} />}</span><div><strong>{run.goal}</strong><small>{run.executionMode} · {run.currentStep}/{run.maxSteps} 步</small></div><span className="tag">{run.status}</span></article>)}</div></section>
        <section className="panel"><header><div><h2>数字员工</h2><p>实际平台注册定义</p></div><Link to="/employees">管理</Link></header><div className="rows">{state.data?.agents.slice(0, 5).map((agent) => <article className="agent-row" key={agent.id}><span className="agent-icon"><Bot size={18} /></span><div><strong>{agent.name}</strong><small>{agent.roleName} · v{agent.version}</small></div><span className="tag">{agent.status}</span></article>)}</div></section>
      </div>
    </AsyncView>
  </div>
}
