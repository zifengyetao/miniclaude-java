/**
 * 平台管理页：版本化资产草稿、策略试算和审计记录查询。
 *
 * Tab 状态：assets | policy | audit — 仅切换展示，不触发额外 API（数据已在 state useAsync 中加载）。
 *
 * API 意图：
 * - governanceApi.assets / audits — 首屏并行加载
 * - createAsset — 创建 DRAFT 资产
 * - evaluatePolicy — 确定性策略试算（结果不可当作后续调用的授权令牌）
 *
 * 安全：前端表单不授予管理权限；写操作、策略判定与审计完整性均以服务端为准。
 */
import { FileKey, Play, ScrollText, ShieldCheck } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { governanceApi } from '../../shared/api/client'
import type { VersionedAsset } from '../../shared/api/types'
import { AsyncView } from '../../shared/components/AsyncView'
import { useAsync } from '../../shared/hooks/useAsync'

export function AdminPage() {
  const state = useAsync(async () => {
    const [assets, audits] = await Promise.all([governanceApi.assets(), governanceApi.audits()])
    return { assets, audits }
  })
  const [tab, setTab] = useState<'assets' | 'policy' | 'audit'>('assets')
  const [asset, setAsset] = useState({ type: 'PROMPT' as VersionedAsset['type'], key: '', version: '1.0.0', content: '' })
  const [policy, setPolicy] = useState({ action: 'tool.execute', resource: 'workspace:*' })
  const [result, setResult] = useState('')
  const [busy, setBusy] = useState(false)

  /** 创建资产草稿；成功后清空 key/content 并 reload，保留 type/version 便于连续创建。 */
  async function createAsset(event: FormEvent) {
    event.preventDefault(); setBusy(true)
    try { await governanceApi.createAsset(asset); setAsset({ ...asset, key: '', content: '' }); await state.reload() }
    catch (cause) { setResult(cause instanceof Error ? cause.message : '创建失败') } finally { setBusy(false) }
  }

  /** 策略试算：将 outcome: reason 写入 result 展示区，仅供运维解释。 */
  async function evaluate(event: FormEvent) {
    event.preventDefault(); setBusy(true)
    try { const decision = await governanceApi.evaluatePolicy(policy.action, policy.resource); setResult(`${decision.outcome}: ${decision.reason}`) }
    catch (cause) { setResult(cause instanceof Error ? cause.message : '评估失败') } finally { setBusy(false) }
  }

  return <div className="page"><div className="page-heading"><div><span className="eyebrow">Governance</span><h1>平台管理</h1><p>管理版本化资产、验证策略并追溯审计记录。</p></div></div><div className="tabs"><button className={tab === 'assets' ? 'active' : ''} onClick={() => setTab('assets')}><FileKey size={15} />资产注册表</button><button className={tab === 'policy' ? 'active' : ''} onClick={() => setTab('policy')}><ShieldCheck size={15} />策略验证</button><button className={tab === 'audit' ? 'active' : ''} onClick={() => setTab('audit')}><ScrollText size={15} />审计日志</button></div><AsyncView loading={state.loading} error={state.error} onRetry={state.reload}>
      {tab === 'assets' && <div className="admin-grid"><section className="panel table-panel"><header><h2>版本化资产</h2><span>{state.data?.assets.length} 项</span></header><table><thead><tr><th>键</th><th>类型</th><th>版本</th><th>状态</th></tr></thead><tbody>{state.data?.assets.map((item) => <tr key={item.id}><td><strong>{item.key}</strong><small>{item.contentHash?.slice(0, 12)}</small></td><td>{item.type}</td><td>{item.version}</td><td><span className="tag">{item.status}</span></td></tr>)}</tbody></table></section><form className="side-form" onSubmit={createAsset}><h2>创建草稿资产</h2><label>类型<select value={asset.type} onChange={(e) => setAsset({ ...asset, type: e.target.value as VersionedAsset['type'] })}>{['PROMPT','RULE','SKILL','GRAPH','VERIFIER','EVAL_SET'].map((v) => <option key={v}>{v}</option>)}</select></label><label>资产键<input required value={asset.key} onChange={(e) => setAsset({ ...asset, key: e.target.value })} /></label><label>版本<input required value={asset.version} onChange={(e) => setAsset({ ...asset, version: e.target.value })} /></label><label>内容<textarea required value={asset.content} onChange={(e) => setAsset({ ...asset, content: e.target.value })} /></label><button className="button primary full" disabled={busy}>创建草稿</button></form></div>}
      {tab === 'policy' && <form className="policy-tester" onSubmit={evaluate}><ShieldCheck size={28} /><h2>确定性策略评估</h2><p>提交 action 与 resource，查看当前租户策略判定。</p><label>Action<input value={policy.action} onChange={(e) => setPolicy({ ...policy, action: e.target.value })} /></label><label>Resource<input value={policy.resource} onChange={(e) => setPolicy({ ...policy, resource: e.target.value })} /></label><button className="button primary" disabled={busy}><Play size={14} />执行评估</button>{result && <div className="notice">{result}</div>}</form>}
      {tab === 'audit' && <section className="panel table-panel"><header><h2>不可变审计日志</h2><span>{state.data?.audits.length} 条</span></header><table><thead><tr><th>动作</th><th>资源</th><th>操作者</th><th>时间</th></tr></thead><tbody>{state.data?.audits.map((entry, index) => <tr key={entry.id || index}><td>{entry.action as string || '-'}</td><td>{entry.resourceType as string || '-'} / {entry.resourceId as string || '-'}</td><td>{entry.actorId as string || '-'}</td><td>{entry.createdAt ? new Date(entry.createdAt as string).toLocaleString() : '-'}</td></tr>)}</tbody></table></section>}
    </AsyncView></div>
}
