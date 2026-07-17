/**
 * 数字员工页面负责查询 Agent 定义并提交新建草稿。
 * 前端字段枚举和长度限制只用于交互，服务端仍须执行权限、风险级别和数据合法性校验。
 */
import { Bot, Plus, X } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { platformApi } from '../../shared/api/client'
import type { CreateAgentRequest, EvolutionLevel, ExecutionMode, RiskLevel } from '../../shared/api/types'
import { AsyncView } from '../../shared/components/AsyncView'
import { useAsync } from '../../shared/hooks/useAsync'

const initial: CreateAgentRequest = { name: '', description: '', roleName: '', riskLevel: 'LOW', evolutionLevel: 'L1', executionModes: ['PLAN_EXECUTE'] }

export function EmployeesPage() {
  const agents = useAsync(platformApi.agents.list)
  const [open, setOpen] = useState(false)
  const [form, setForm] = useState(initial)
  const [saving, setSaving] = useState(false)
  const [submitError, setSubmitError] = useState('')

  /** 提交数字员工定义，成功后关闭表单并重新读取服务端列表。 */
  async function submit(event: FormEvent) {
    event.preventDefault()
    setSaving(true); setSubmitError('')
    try {
      await platformApi.agents.create(form)
      setOpen(false); setForm(initial); await agents.reload()
    } catch (cause) { setSubmitError(cause instanceof Error ? cause.message : '创建失败') }
    finally { setSaving(false) }
  }

  return <div className="page">
    <div className="page-heading"><div><span className="eyebrow">Workforce</span><h1>数字员工</h1><p>创建并管理可版本化的 Agent 定义。</p></div><button className="button primary" onClick={() => setOpen(true)}><Plus size={15} />创建数字员工</button></div>
    <AsyncView loading={agents.loading} error={agents.error} empty={agents.data?.length === 0} emptyText="尚未创建数字员工" onRetry={agents.reload}>
      <section className="card-grid">{agents.data?.map((agent) => <article className="agent-card" key={agent.id}><header><span className="agent-icon large"><Bot size={22} /></span><span className="tag">{agent.status}</span></header><h2>{agent.name}</h2><small>{agent.roleName} · v{agent.version}</small><p>{agent.description}</p><div className="tags">{agent.executionModes.map((mode) => <span key={mode}>{mode}</span>)}</div><footer><span>风险 {agent.riskLevel}</span><strong>{agent.evolutionLevel}</strong></footer></article>)}</section>
    </AsyncView>
    {open && <div className="modal-backdrop"><form className="modal" onSubmit={submit}><header><div><h2>创建数字员工</h2><p>字段与 CreateAgentDefinitionRequest 保持一致。</p></div><button type="button" aria-label="关闭" onClick={() => setOpen(false)}><X size={18} /></button></header><div className="form-grid"><label>名称<input required maxLength={120} value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label><label>角色名<input required maxLength={120} value={form.roleName} onChange={(e) => setForm({ ...form, roleName: e.target.value })} /></label><label className="full">描述<textarea required maxLength={1000} value={form.description} onChange={(e) => setForm({ ...form, description: e.target.value })} /></label><label>风险级别<select value={form.riskLevel} onChange={(e) => setForm({ ...form, riskLevel: e.target.value as RiskLevel })}>{['LOW','MEDIUM','HIGH','REGULATED'].map((v) => <option key={v}>{v}</option>)}</select></label><label>进化级别<select value={form.evolutionLevel} onChange={(e) => setForm({ ...form, evolutionLevel: e.target.value as EvolutionLevel })}>{['L0','L1','L2','L3'].map((v) => <option key={v}>{v}</option>)}</select></label><label className="full">执行模式<select value={form.executionModes[0]} onChange={(e) => setForm({ ...form, executionModes: [e.target.value as ExecutionMode] })}>{['CHAT','PLAN_EXECUTE','GOAL','GRAPH'].map((v) => <option key={v}>{v}</option>)}</select></label>{submitError && <p className="form-error full">{submitError}</p>}</div><footer><button type="button" className="button secondary" onClick={() => setOpen(false)}>取消</button><button className="button primary" disabled={saving}>{saving ? '创建中' : '创建'}</button></footer></form></div>}
  </div>
}
