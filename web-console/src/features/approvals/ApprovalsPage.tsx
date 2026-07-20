/**
 * 审批中心：聚合各运行的审批请求，并允许人工审查动作参数后批准或拒绝。
 *
 * 数据加载：
 * - 先 list 全部 runs，再对每个 run 并行 runs.approvals，flat 成单一列表
 * - 无专用「全局 pending 审批」API 时的 N+1 折中；运行数很大时可能较慢
 *
 * 决策 API：platformApi.runs.decide 携带 actionParameters 原文与 reason；
 * 服务端必须校验审批权限、参数哈希绑定与状态机。
 *
 * 安全：页面按钮与 PENDING 过滤不是安全边界；过期/已决审批不应被重复提交（服务端拒绝）。
 */
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

  /**
   * 提交当前审批决定并刷新列表。
   * 使用 selected.actionParameters 原文，确保与 awaitApproval 时哈希一致。
   * busy 仅防止界面内重复点击，不替代服务端幂等与并发校验。
   */
  async function decide(decision: 'APPROVED' | 'REJECTED') {
    if (!selected) return
    setBusy(true)
    try { await platformApi.runs.decide(selected.runId, selected.id, selected.actionParameters, decision, reason); setSelected(null); setReason(''); await approvals.reload() }
    finally { setBusy(false) }
  }

  const pending = approvals.data?.filter((item) => item.status === 'PENDING') ?? []
  return <div className="page"><div className="page-heading"><div><span className="eyebrow">Human in the loop</span><h1>审批中心</h1><p>审查运行中的高风险动作并提交不可变决策。</p></div><span className="counter">{pending.length} 项待处理</span></div>{/* 证据区：展示 actionParameters、runId、expiresAt 供人工复核。 */}<AsyncView loading={approvals.loading} error={approvals.error} empty={approvals.data?.length === 0} emptyText="暂无审批请求" onRetry={approvals.reload}><section className="approval-grid">{approvals.data?.map((approval) => <article className="approval-card" key={approval.id}><header><span className="agent-icon"><ShieldCheck size={18} /></span><span className="tag">{approval.status}</span></header><h2>{approval.actionType}</h2><p>{approval.actionParameters}</p><dl><div><dt>运行</dt><dd>{approval.runId.slice(0, 12)}</dd></div><div><dt>步骤</dt><dd>{approval.stepId}</dd></div><div><dt>到期</dt><dd>{new Date(approval.expiresAt).toLocaleString()}</dd></div></dl>{approval.status === 'PENDING' && <button className="button secondary full" onClick={() => setSelected(approval)}>处理审批</button>}</article>)}</section></AsyncView>
    {/* 决策模态框：再次展示原始 JSON 参数，要求填写 reason 以满足审计。 */}{selected && <div className="modal-backdrop"><section className="modal"><header><div><h2>审批决策</h2><p>{selected.actionType} · {selected.stepId}</p></div><button onClick={() => setSelected(null)}><X size={18} /></button></header><div className="form-grid"><label className="full">决策原因<textarea value={reason} onChange={(e) => setReason(e.target.value)} placeholder="记录批准或拒绝依据" /></label><div className="code-block full">{selected.actionParameters}</div></div><footer><button className="button danger" disabled={busy} onClick={() => void decide('REJECTED')}><X size={14} />拒绝</button><button className="button primary" disabled={busy} onClick={() => void decide('APPROVED')}><Check size={14} />批准</button></footer></section></div>}
  </div>
}
