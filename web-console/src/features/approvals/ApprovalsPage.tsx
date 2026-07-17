import { Check, ShieldCheck, X } from 'lucide-react'
import { useState } from 'react'
import { platformApi } from '../../shared/api/client'
import type { ApprovalRequest } from '../../shared/api/types'
import { AsyncView } from '../../shared/components/AsyncView'
import { useAsync } from '../../shared/hooks/useAsync'

export function ApprovalsPage() {
  const approvals = useAsync(async () => {
    const runs = await platformApi.runs.list()
    return (await Promise.all(runs.map((run) => platformApi.runs.approvals(run.id)))).flat()
  })
  const [selected, setSelected] = useState<ApprovalRequest | null>(null)
  const [reason, setReason] = useState('')
  const [busy, setBusy] = useState(false)

  async function decide(decision: 'APPROVED' | 'REJECTED') {
    if (!selected) return
    setBusy(true)
    try { await platformApi.runs.decide(selected.runId, selected.id, selected.actionParameters, decision, reason); setSelected(null); setReason(''); await approvals.reload() }
    finally { setBusy(false) }
  }

  const pending = approvals.data?.filter((item) => item.status === 'PENDING') ?? []
  return <div className="page"><div className="page-heading"><div><span className="eyebrow">Human in the loop</span><h1>审批中心</h1><p>审查运行中的高风险动作并提交不可变决策。</p></div><span className="counter">{pending.length} 项待处理</span></div><AsyncView loading={approvals.loading} error={approvals.error} empty={approvals.data?.length === 0} emptyText="暂无审批请求" onRetry={approvals.reload}><section className="approval-grid">{approvals.data?.map((approval) => <article className="approval-card" key={approval.id}><header><span className="agent-icon"><ShieldCheck size={18} /></span><span className="tag">{approval.status}</span></header><h2>{approval.actionType}</h2><p>{approval.actionParameters}</p><dl><div><dt>运行</dt><dd>{approval.runId.slice(0, 12)}</dd></div><div><dt>步骤</dt><dd>{approval.stepId}</dd></div><div><dt>到期</dt><dd>{new Date(approval.expiresAt).toLocaleString()}</dd></div></dl>{approval.status === 'PENDING' && <button className="button secondary full" onClick={() => setSelected(approval)}>处理审批</button>}</article>)}</section></AsyncView>
    {selected && <div className="modal-backdrop"><section className="modal"><header><div><h2>审批决策</h2><p>{selected.actionType} · {selected.stepId}</p></div><button onClick={() => setSelected(null)}><X size={18} /></button></header><div className="form-grid"><label className="full">决策原因<textarea value={reason} onChange={(e) => setReason(e.target.value)} placeholder="记录批准或拒绝依据" /></label><div className="code-block full">{selected.actionParameters}</div></div><footer><button className="button danger" disabled={busy} onClick={() => void decide('REJECTED')}><X size={14} />拒绝</button><button className="button primary" disabled={busy} onClick={() => void decide('APPROVED')}><Check size={14} />批准</button></footer></section></div>}
  </div>
}
