/**
 * 任务中心负责创建持久化运行、提交暂停/恢复/取消命令，并展示事件与检查点证据。
 * 当前页面通过普通 API 显式刷新，不消费 SSE；展示状态可能落后于服务端实际进度。
 */
import { Pause, Play, Plus, RotateCcw, Square, X } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { platformApi } from '../../shared/api/client'
import type { AgentRun, CreateRunRequest, ExecutionMode } from '../../shared/api/types'
import { AsyncView } from '../../shared/components/AsyncView'
import { useAsync } from '../../shared/hooks/useAsync'

export function TasksPage() {
  const runs = useAsync(platformApi.runs.list)
  const agents = useAsync(platformApi.agents.list)
  const [selected, setSelected] = useState<AgentRun | null>(null)
  const [open, setOpen] = useState(false)
  const [form, setForm] = useState<CreateRunRequest>({ agentId: '', executionMode: 'PLAN_EXECUTE', goal: '', maxSteps: 30, maxCostUsd: 2, timeoutSeconds: 900 })
  const [busy, setBusy] = useState('')
  const details = useAsync(async () => selected ? Promise.all([platformApi.runs.events(selected.id), platformApi.runs.checkpoints(selected.id)]) : [[], []], [selected?.id])

  /** 创建运行并刷新列表；表单约束只改善输入体验，预算、步数和权限仍由服务端校验。 */
  async function create(event: FormEvent) {
    event.preventDefault(); setBusy('create')
    try { await platformApi.runs.create(form); setOpen(false); await runs.reload() } finally { setBusy('') }
  }
  /**
   * 提交运行控制命令并同步返回快照。
   * busy 只抑制当前页面重复操作，跨标签页并发与状态迁移合法性必须由服务端处理。
   */
  async function control(run: AgentRun, action: 'pause' | 'resume' | 'cancel') {
    setBusy(`${run.id}-${action}`)
    try { const updated = await platformApi.runs.control(run.id, action); setSelected(updated); await runs.reload() } finally { setBusy('') }
  }

  return <div className="page"><div className="page-heading"><div><span className="eyebrow">Durable Runs</span><h1>任务中心</h1><p>创建、暂停、恢复、取消运行，并检查事件和检查点。</p></div><button className="button primary" onClick={() => { setForm({ ...form, agentId: form.agentId || agents.data?.[0]?.id || '' }); setOpen(true) }}><Plus size={15} />新建运行</button></div>
    <AsyncView loading={runs.loading} error={runs.error} empty={runs.data?.length === 0} emptyText="暂无任务运行" onRetry={runs.reload}><div className="split-view"><section className="panel table-panel"><header><h2>全部运行</h2><span>{runs.data?.length} 条</span></header><table><thead><tr><th>目标</th><th>模式</th><th>进度</th><th>状态</th><th>控制</th></tr></thead><tbody>{runs.data?.map((run) => <tr key={run.id} onClick={() => setSelected(run)} className={selected?.id === run.id ? 'selected' : ''}><td><strong>{run.goal}</strong><small>{run.id.slice(0, 8)}</small></td><td>{run.executionMode}</td><td>{run.currentStep}/{run.maxSteps}</td><td><span className="tag">{run.status}</span></td><td><div className="table-actions">{run.status === 'PAUSED' ? <button aria-label="恢复" onClick={(e) => { e.stopPropagation(); void control(run, 'resume') }} disabled={!!busy}><Play size={14} /></button> : <button aria-label="暂停" onClick={(e) => { e.stopPropagation(); void control(run, 'pause') }} disabled={!!busy}><Pause size={14} /></button>}<button aria-label="取消" onClick={(e) => { e.stopPropagation(); void control(run, 'cancel') }} disabled={!!busy}><Square size={13} /></button></div></td></tr>)}</tbody></table></section>
      <aside className="detail-panel">{selected ? <><header><div><h2>运行详情</h2><p>{selected.id}</p></div><button onClick={() => void details.reload()}><RotateCcw size={14} /></button></header><dl><div><dt>状态</dt><dd>{selected.status}</dd></div><div><dt>成本</dt><dd>${selected.costUsd ?? 0}</dd></div><div><dt>版本</dt><dd>{selected.version}</dd></div><div><dt>超时</dt><dd>{selected.timeoutAt || '未设置'}</dd></div></dl><AsyncView loading={details.loading} error={details.error} onRetry={details.reload}><h3>事件</h3><div className="event-list">{details.data?.[0].map((event) => <article key={event.id}><i /><span><strong>{event.type}</strong><small>#{event.sequence} · {event.stepId || 'run'}</small></span></article>)}</div><h3>检查点</h3><div className="event-list">{details.data?.[1].map((checkpoint) => <article key={checkpoint.id}><i /><span><strong>{checkpoint.stepId}</strong><small>#{checkpoint.sequence} · v{checkpoint.version}</small></span></article>)}</div></AsyncView></> : <div className="mini-empty"><Play /><span>选择一个运行查看详情</span></div>}</aside></div></AsyncView>
    {open && <div className="modal-backdrop"><form className="modal" onSubmit={create}><header><div><h2>新建持久化运行</h2><p>将调用 platform/runs 创建运行。</p></div><button type="button" onClick={() => setOpen(false)}><X size={18} /></button></header><div className="form-grid"><label className="full">数字员工<select required value={form.agentId} onChange={(e) => setForm({ ...form, agentId: e.target.value })}><option value="">请选择</option>{agents.data?.map((agent) => <option value={agent.id} key={agent.id}>{agent.name}</option>)}</select></label><label>执行模式<select value={form.executionMode} onChange={(e) => setForm({ ...form, executionMode: e.target.value as ExecutionMode })}>{['CHAT','PLAN_EXECUTE','GOAL','GRAPH'].map((value) => <option key={value}>{value}</option>)}</select></label><label>最大步数<input type="number" min="1" max="200" value={form.maxSteps} onChange={(e) => setForm({ ...form, maxSteps: Number(e.target.value) })} /></label><label className="full">目标<textarea required maxLength={2000} value={form.goal} onChange={(e) => setForm({ ...form, goal: e.target.value })} /></label><label>成本上限 USD<input type="number" min=".01" step=".01" value={form.maxCostUsd} onChange={(e) => setForm({ ...form, maxCostUsd: Number(e.target.value) })} /></label><label>超时秒数<input type="number" min="1" value={form.timeoutSeconds} onChange={(e) => setForm({ ...form, timeoutSeconds: Number(e.target.value) })} /></label></div><footer><button type="button" className="button secondary" onClick={() => setOpen(false)}>取消</button><button className="button primary" disabled={busy === 'create'}>启动运行</button></footer></form></div>}
  </div>
}
