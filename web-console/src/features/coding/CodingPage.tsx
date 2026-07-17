/**
 * Coding Agent 页面负责选择场景模板、启动/继续运行，并显式刷新运行状态和产物。
 * 页面使用普通 API 而非 SSE，终端输出与产物列表都是查询时快照，不保证实时连续性。
 */
import { FileCode2, Folder, Play, RefreshCw, TerminalSquare } from 'lucide-react'
import { useState } from 'react'
import { scenarioApi } from '../../shared/api/client'
import type { AgentRun, ScenarioArtifact } from '../../shared/api/types'
import { AsyncView } from '../../shared/components/AsyncView'
import { useAsync } from '../../shared/hooks/useAsync'

export function CodingPage() {
  const templates = useAsync(scenarioApi.templates)
  const [scenario, setScenario] = useState('')
  const [goal, setGoal] = useState('分析当前代码库并给出可验证的改进方案')
  const [run, setRun] = useState<AgentRun | null>(null)
  const [artifacts, setArtifacts] = useState<ScenarioArtifact[]>([])
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  /** 使用当前模板和目标启动场景；仓库标识及输入仍须由服务端校验和授权。 */
  async function start() {
    const id = scenario || templates.data?.[0]?.id
    if (!id) return
    setBusy(true); setError('')
    try { setScenario(id); setRun(await scenarioApi.start(id, { goal, repository: 'miniclaude-java' })); setArtifacts([]) }
    catch (cause) { setError(cause instanceof Error ? cause.message : '启动失败') }
    finally { setBusy(false) }
  }
  /** 并行拉取最新运行状态与产物，更新右侧证据快照。 */
  async function refresh() {
    if (!run || !scenario) return
    setBusy(true)
    try { setRun(await scenarioApi.status(scenario, run.id)); setArtifacts(await scenarioApi.artifacts(scenario, run.id)) }
    catch (cause) { setError(cause instanceof Error ? cause.message : '刷新失败') }
    finally { setBusy(false) }
  }
  /** 请求服务端继续已暂停的场景；前端状态不决定该运行是否允许继续。 */
  async function continueRun() {
    if (!run || !scenario) return
    setBusy(true)
    try { setRun(await scenarioApi.continue(scenario, run.id)) } finally { setBusy(false) }
  }
  return <div className="coding-page"><header className="studio-header"><div><span className="eyebrow">Scenario Runtime</span><h1>Coding Agent</h1></div><div><select aria-label="场景模板" value={scenario} onChange={(e) => setScenario(e.target.value)}><option value="">选择场景</option>{templates.data?.map((template) => <option value={template.id} key={template.id}>{template.name} · {template.version}</option>)}</select><button className="button primary" disabled={busy} onClick={() => void start()}><Play size={14} />启动场景</button></div></header><div className="coding-layout"><aside className="files-pane"><h2>场景模板</h2><AsyncView loading={templates.loading} error={templates.error} empty={templates.data?.length === 0} onRetry={templates.reload}>{templates.data?.map((template) => <button className={scenario === template.id ? 'active' : ''} key={template.id} onClick={() => setScenario(template.id)}><Folder size={15} /><span><strong>{template.name}</strong><small>{template.id}</small></span></button>)}</AsyncView></aside><section className="editor-pane"><header><FileCode2 size={15} />scenario-input.json</header><textarea value={goal} onChange={(e) => setGoal(e.target.value)} aria-label="Coding 目标" /><div className="terminal"><header><TerminalSquare size={14} />RUN OUTPUT</header>{error && <p className="error-text">{error}</p>}{run ? <><p><b>$</b> scenario start {scenario}</p><p>run_id: {run.id}</p><p>status: <span>{run.status}</span></p><p>step: {run.currentStep}/{run.maxSteps}</p></> : <p><b>$</b> 等待启动场景</p>}</div></section><aside className="run-pane"><header><h2>Scenario Run</h2>{run && <span className="tag">{run.status}</span>}</header>{run ? <><dl><div><dt>运行 ID</dt><dd>{run.id.slice(0, 12)}</dd></div><div><dt>模式</dt><dd>{run.executionMode}</dd></div><div><dt>成本</dt><dd>${run.costUsd ?? 0}</dd></div></dl><div className="stack-actions"><button className="button secondary full" onClick={() => void refresh()} disabled={busy}><RefreshCw size={14} />刷新状态与产物</button><button className="button primary full" onClick={() => void continueRun()} disabled={busy}>继续运行</button></div><h3>Artifacts</h3>{artifacts.length === 0 ? <div className="mini-empty"><FileCode2 /><span>暂无场景产物</span></div> : artifacts.map((artifact) => <article className="artifact-item" key={artifact.id}><FileCode2 size={16} /><div><strong>{artifact.name}</strong><small>{artifact.type}</small></div></article>)}</> : <div className="mini-empty"><Play /><span>选择模板并启动 Coding 场景</span></div>}</aside></div></div>
}
