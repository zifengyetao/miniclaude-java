/**
 * Chat 页面：管理当前浏览器内消息列表、后端会话选择、最近运行时间线与响应元数据。
 *
 * 状态与 Hooks：
 * - sessions / runs：useAsync 分别加载 /sessions 与 /platform/runs
 * - sessionId：当前绑定的后端会话；空字符串表示下次 send 将创建新会话
 * - messages：仅存在于浏览器内存，刷新页面即丢失（无历史消息 API）
 * - sending / sendError：发送中禁用按钮并展示错误
 *
 * API 意图：
 * - chatApi.send：POST /chat，返回 sessionId + reply + tokens
 * - sessions.reload：发送成功后刷新侧栏会话列表
 *
 * 限制：一次性请求/响应，无 SSE 流式增量、断线重连或历史回放。
 */
import { FileText, MessageSquarePlus, Send, Sparkles } from 'lucide-react'
import { useState, type FormEvent } from 'react'
import { chatApi, platformApi } from '../../shared/api/client'
import type { ChatResponse } from '../../shared/api/types'
import { AsyncView } from '../../shared/components/AsyncView'
import { useAsync } from '../../shared/hooks/useAsync'

/** 浏览器内消息条目；assistant 可附带 ChatResponse 元数据供 Artifacts 面板展示。 */
interface Message { role: 'user' | 'assistant'; text: string; meta?: ChatResponse }

export function ChatPage() {
  const sessions = useAsync(chatApi.sessions)
  const runs = useAsync(platformApi.runs.list)
  const [sessionId, setSessionId] = useState('')
  const [input, setInput] = useState('')
  const [messages, setMessages] = useState<Message[]>([])
  const [sending, setSending] = useState(false)
  const [sendError, setSendError] = useState('')

  /**
   * 发送消息流程：
   * 1. 阻止空输入与默认表单提交
   * 2. 乐观追加用户消息到 UI
   * 3. 调用 chatApi.send（sessionId 可选，后端可创建或续聊）
   * 4. 成功后更新 sessionId、追加 assistant 消息、刷新 sessions
   * 失败：仅 setSendError，不自动重试（无客户端幂等键，重试可能重复计费）
   */
  async function send(event: FormEvent) {
    event.preventDefault()
    if (!input.trim()) return
    const text = input.trim()
    setMessages((current) => [...current, { role: 'user', text }]); setInput(''); setSending(true); setSendError('')
    try {
      const response = await chatApi.send(text, sessionId || undefined)
      setSessionId(response.sessionId)
      setMessages((current) => [...current, { role: 'assistant', text: response.reply, meta: response }])
      await sessions.reload()
    } catch (cause) { setSendError(cause instanceof Error ? cause.message : '发送失败') }
    finally { setSending(false) }
  }

  const recentRuns = runs.data?.slice(0, 4) ?? []
  const artifacts = messages.filter((message) => message.meta)

  return <div className="chat-layout"><aside className="conversation-pane"><header><strong>会话</strong>{/* 新建会话：清空本地 sessionId 与 messages，不调用后端 delete。 */}<button aria-label="新建会话" onClick={() => { setSessionId(''); setMessages([]) }}><MessageSquarePlus size={17} /></button></header><AsyncView loading={sessions.loading} error={sessions.error} empty={sessions.data?.length === 0} onRetry={sessions.reload}><div className="conversation-list">{sessions.data?.map((session) => <button className={session.id === sessionId ? 'active' : ''} key={session.id} onClick={() => { setSessionId(session.id); setMessages([]) }}><strong>{session.title || '未命名会话'}</strong><small>{session.model}</small></button>)}</div></AsyncView></aside><section className="chat-main"><header><div><strong>{sessionId ? '继续会话' : '新会话'}</strong><small>{sessionId || '发送首条消息后创建会话'}</small></div></header><div className="message-list">{messages.length === 0 && <div className="chat-empty"><Sparkles size={25} /><h2>开始与 MiniClaude 协作</h2><p>消息将通过真实 `/api/v1/chat` 接口发送。</p></div>}{messages.map((message, index) => <article className={`message ${message.role}`} key={`${message.role}-${index}`}><strong>{message.role === 'user' ? '你' : 'MiniClaude'}</strong><p>{message.text}</p>{message.meta && <small>{message.meta.model} · {Object.values(message.meta.tokens).reduce((a, b) => a + b, 0)} tokens</small>}</article>)}</div><section className="timeline-panel"><header><strong>Plan / Run 时间线</strong><span>平台运行</span></header><AsyncView loading={runs.loading} error={runs.error} empty={recentRuns.length === 0} onRetry={runs.reload}><div>{recentRuns.map((run) => <article key={run.id}><i className={run.status.toLowerCase()} /><span><strong>{run.goal}</strong><small>{run.executionMode} · {run.currentStep}/{run.maxSteps}</small></span><b>{run.status}</b></article>)}</div></AsyncView></section><form className="composer" onSubmit={send}><textarea aria-label="消息" value={input} onChange={(e) => setInput(e.target.value)} placeholder="描述你的目标或问题" /><div>{sendError && <span className="form-error">{sendError}</span>}<button aria-label="发送" disabled={sending}><Send size={16} /></button></div></form></section><aside className="artifact-pane"><header><strong>Artifacts</strong><span>{artifacts.length}</span></header><p>本次浏览器会话的结构化响应</p>{artifacts.length === 0 ? <div className="mini-empty"><FileText /><span>回复后显示模型与 Token 信息</span></div> : artifacts.map((item, index) => <article key={index}><FileText size={18} /><div><strong>chat-response-{index + 1}.json</strong><small>{item.meta?.model}</small></div></article>)}</aside></div>
}
