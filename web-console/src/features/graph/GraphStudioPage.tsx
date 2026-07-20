/**
 * Graph Studio：Graph 定义 JSON 编辑、结构预览与服务端校验结果展示。
 *
 * 状态：
 * - graph：编辑器内 JSON 字符串（受控 textarea）
 * - result / error / loading：校验 API 的结果、客户端 JSON.parse 错误或请求中标志
 *
 * 左侧/中间预览基于 initialGraph 静态示例，不代表右侧编辑器当前内容；
 * 实际送检 payload 以 textarea 为准。
 *
 * API：platformApi.graphs.validate → POST /platform/graphs/validate
 * 浏览器 JSON.parse 仅是输入预检查；环、 unreachable、limits 等权威校验在后端。
 */
import { AlertTriangle, CheckCircle2, GitFork, Play } from 'lucide-react'
import { useState } from 'react'
import { platformApi } from '../../shared/api/client'
import type { GraphRequest, GraphValidationResult } from '../../shared/api/types'

/** 默认示例图：供首次打开时填充编辑器，亦用于左侧静态节点列表与中间 flow 预览。 */
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
   * 校验流程：
   * 1. 清空上次 result/error，进入 loading
   * 2. JSON.parse(graph) — 失败则 catch 展示「校验失败」类消息
   * 3. platformApi.graphs.validate — 展示 errors/warnings 合并列表
   */
  async function validate() {
    setLoading(true); setError(''); setResult(null)
    try { setResult(await platformApi.graphs.validate(JSON.parse(graph) as GraphRequest)) }
    catch (cause) { setError(cause instanceof Error ? cause.message : '校验失败') }
    finally { setLoading(false) }
  }

  return <div className="studio-page"><header className="studio-header"><div><span className="eyebrow">Graph Compiler</span><h1>Graph Studio</h1></div><button className="button primary" onClick={() => void validate()} disabled={loading}><Play size={14} />{loading ? '校验中' : '验证图定义'}</button></header>{/* 左侧：静态 initialGraph 节点列表，非 live 解析。 */}<div className="graph-layout"><aside><h2>节点</h2>{initialGraph.nodes.map((node) => <article key={node.id}><span><GitFork size={15} /></span><div><strong>{node.id}</strong><small>{node.type}</small></div></article>)}</aside><section className="graph-canvas"><div className="graph-flow">{initialGraph.nodes.map((node, index) => <div key={node.id}><article><GitFork size={18} /><strong>{node.id}</strong><small>{node.type}</small></article>{index < initialGraph.nodes.length - 1 && <i />}</div>)}</div></section>{/* 右侧：可编辑 JSON + 服务端校验结果；本地 parse 成功 ≠ 图可执行。 */}<aside className="graph-editor"><h2>Graph JSON</h2><textarea aria-label="Graph JSON" value={graph} onChange={(e) => setGraph(e.target.value)} spellCheck={false} />{error && <div className="validation-result invalid"><AlertTriangle size={16} /><span>{error}</span></div>}{result && <div className={`validation-result ${result.valid ? 'valid' : 'invalid'}`}>{result.valid ? <CheckCircle2 size={16} /> : <AlertTriangle size={16} />}<div><strong>{result.valid ? '图定义有效' : '发现校验错误'}</strong>{[...result.errors, ...result.warnings].map((item) => <p key={item}>{item}</p>)}</div></div>}</aside></div></div>
}
