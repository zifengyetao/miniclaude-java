/**
 * 进化中心：汇总观测、候选与 anti-rot 发现，并提交评审、推广和回滚动作。
 *
 * 数据：observations / candidates / findings 三路并行加载。
 *
 * act(id, action)：
 * - review：构造 reviewerRole/decision/comment 载荷
 * - promote / rollback：不同 body 形状，均由 evolutionApi.action 提交
 * - 服务端校验状态机、角色与 L3 allowlist，前端 busy 仅防重复点击
 *
 * scan()：触发 anti-rot 扫描 API，无 SSE 进度通知。
 */
import { Activity, RefreshCw, RotateCcw, ShieldAlert, ThumbsUp } from 'lucide-react'
import { useState } from 'react'
import { evolutionApi } from '../../shared/api/client'
import { AsyncView } from '../../shared/components/AsyncView'
import { useAsync } from '../../shared/hooks/useAsync'

export function EvolutionPage() {
  const state = useAsync(async () => {
    const [observations, candidates, findings] = await Promise.all([evolutionApi.observations(), evolutionApi.candidates(), evolutionApi.findings()])
    return { observations, candidates, findings }
  })
  const [busy, setBusy] = useState('')
  const [message, setMessage] = useState('')

  /**
   * 为候选构造治理动作载荷并提交。
   * 成功 setMessage 并 reload 全量证据；失败展示 API 错误文案。
   */
  async function act(id: string, action: 'review' | 'promote' | 'rollback') {
    setBusy(`${id}-${action}`); setMessage('')
    try {
      const body = action === 'review' ? { reviewerRole: 'PLATFORM_OWNER', decision: 'APPROVED', comment: 'Approved in web console' } : action === 'promote' ? { automatic: false } : { reason: 'Manual rollback from web console' }
      await evolutionApi.action(id, action, body); setMessage(`${action} 已提交`); await state.reload()
    } catch (cause) { setMessage(cause instanceof Error ? cause.message : '操作失败') } finally { setBusy('') }
  }

  /** Anti-rot 启发式扫描；完成后 reload findings 计数与列表。 */
  async function scan() {
    setBusy('scan')
    try { await evolutionApi.scan(); setMessage('Anti-rot 扫描完成'); await state.reload() }
    catch (cause) { setMessage(cause instanceof Error ? cause.message : '扫描失败') } finally { setBusy('') }
  }

  return <div className="page"><div className="page-heading"><div><span className="eyebrow">Governed Evolution</span><h1>进化中心</h1><p>基于观测、候选与 anti-rot 发现推进受治理演进。</p></div><button className="button secondary" onClick={() => void scan()} disabled={!!busy}><RefreshCw size={14} />Anti-rot 扫描</button></div>{message && <div className="notice">{message}</div>}{/* 候选行内 review/promote/rollback 按钮；观测区仅展示最近 8 条。 */}<AsyncView loading={state.loading} error={state.error} onRetry={state.reload}><section className="metrics compact"><article><Activity /><span>观测</span><strong>{state.data?.observations.length ?? 0}</strong></article><article><ThumbsUp /><span>候选</span><strong>{state.data?.candidates.length ?? 0}</strong></article><article><ShieldAlert /><span>Anti-rot</span><strong>{state.data?.findings.length ?? 0}</strong></article></section><div className="dashboard-grid"><section className="panel"><header><div><h2>进化候选</h2><p>审查并推进候选版本</p></div></header><div className="rows">{state.data?.candidates.length === 0 && <div className="mini-empty"><Activity /><span>暂无候选</span></div>}{state.data?.candidates.map((candidate) => <article className="candidate-row" key={candidate.id}><div><strong>{candidate.assetKey as string || candidate.id}</strong><small>{candidate.proposedVersion as string || '待定版本'} · {candidate.status as string || 'UNKNOWN'}</small></div><div className="table-actions"><button title="批准评审" disabled={!!busy} onClick={() => void act(candidate.id, 'review')}><ThumbsUp size={14} /></button><button title="推广" disabled={!!busy} onClick={() => void act(candidate.id, 'promote')}><Activity size={14} /></button><button title="回滚" disabled={!!busy} onClick={() => void act(candidate.id, 'rollback')}><RotateCcw size={14} /></button></div></article>)}</div></section><section className="panel"><header><div><h2>最新观测</h2><p>运行反馈与归因证据</p></div></header><div className="rows">{state.data?.observations.slice(0, 8).map((item) => <article className="observation-row" key={item.id}><span className="status-icon"><Activity size={16} /></span><div><strong>{item.summary || item.sourceType || item.id}</strong><small>{item.attributionCategory || item.sourceId || '未分类'}</small></div></article>)}</div></section></div></AsyncView></div>
}
