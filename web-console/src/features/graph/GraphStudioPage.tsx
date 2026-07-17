/**
 * Graph Studio 提供图定义 JSON 编辑、结构预览和服务端校验结果展示。
 * 浏览器 JSON 解析与可视化都不是执行安全边界，权威结构、资源限制和引用校验由 API 完成。
 */
import { AlertTriangle, CheckCircle2, GitFork, Play } from 'lucide-react'
import { useState } from 'react'
import { platformApi } from '../../shared/api/client'
import type { GraphRequest, GraphValidationResult } from '../../shared/api/types'

const initialGraph: GraphRequest = {
  name: 'research-flow', version: '1.0.0', entryNode: 'plan',
  nodes: [{ id: 'plan', type: 'PLANNER', reference: 'planner-v1' }, { id: 'execute', type: 'EXECUTOR', reference: 'executor-v1' }, { id: 'done', type: 'TERMINAL', reference: '' }],
  edges: [{ from: 'plan', to: 'execute', condition: 'planned' }, { from: 'execute', to: 'done', condition: 'verified' }],
  limits: { maxSteps: 30, maxIterations: 5, maxCostUsd: 2 },
}

export function GraphStudioPage() {
  const [graph, setGraph] = useState(JSON.stringify(initialGraph, null, 2))
  const [result, setResult] = useState<GraphValidationResult | null>(null)
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  /**
   * 先解析编辑器 JSON，再提交服务端做 Graph 语义校验，并维护单次请求的结果状态。
   * loading 只防止当前按钮重复点击，不代表服务端支持取消或并发去重。
   */
  async function validate() {
    setLoading(true); setError(''); setResult(null)
    try { setResult(await platformApi.graphs.validate(JSON.parse(graph) as GraphRequest)) }
    catch (cause) { setError(cause instanceof Error ? cause.message : '校验失败') }
    finally { setLoading(false) }
  }
  return <div className="studio-page"><header className="studio-header"><div><span className="eyebrow">Graph Compiler</span><h1>Graph Studio</h1></div><button className="button primary" onClick={() => void validate()} disabled={loading}><Play size={14} />{loading ? '校验中' : '验证图定义'}</button></header>{/* Graph 工作区：结构预览使用示例图，右侧编辑器内容才是实际送检输入。 */}<div className="graph-layout"><aside><h2>节点</h2>{initialGraph.nodes.map((node) => <article key={node.id}><span><GitFork size={15} /></span><div><strong>{node.id}</strong><small>{node.type}</small></div></article>)}</aside><section className="graph-canvas"><div className="graph-flow">{initialGraph.nodes.map((node, index) => <div key={node.id}><article><GitFork size={18} /><strong>{node.id}</strong><small>{node.type}</small></article>{index < initialGraph.nodes.length - 1 && <i />}</div>)}</div></section>{/* 证据展示区：保留服务端返回的错误与警告，避免把本地解析成功误认为可执行。 */}<aside className="graph-editor"><h2>Graph JSON</h2><textarea aria-label="Graph JSON" value={graph} onChange={(e) => setGraph(e.target.value)} spellCheck={false} />{error && <div className="validation-result invalid"><AlertTriangle size={16} /><span>{error}</span></div>}{result && <div className={`validation-result ${result.valid ? 'valid' : 'invalid'}`}>{result.valid ? <CheckCircle2 size={16} /> : <AlertTriangle size={16} />}<div><strong>{result.valid ? '图定义有效' : '发现校验错误'}</strong>{[...result.errors, ...result.warnings].map((item) => <p key={item}>{item}</p>)}</div></div>}</aside></div></div>
}
