/**
 * 统一渲染数据区域的加载、失败、空结果和成功内容，避免各页面重复状态分支。
 *
 * 组件职责：
 * - 按固定优先级渲染：loading → error → empty → children
 * - 只展示调用方传入的快照，不推断请求是否仍在服务端执行
 * - onRetry 可选；未提供时错误态不展示重试按钮
 */
import { AlertCircle, Inbox, LoaderCircle, RefreshCw } from 'lucide-react'
import type { ReactNode } from 'react'

interface Props {
  /** 为 true 时展示加载 spinner，children 不可见。 */
  loading: boolean
  /** 非 null 时展示错误面板；优先于 empty 与 children。 */
  error: string | null
  /** 为 true 且非 loading/error 时展示空态。 */
  empty?: boolean
  emptyText?: string
  /** 错误态重试回调，通常传入 useAsync 的 reload。 */
  onRetry?: () => void
  children: ReactNode
}

/**
 * 按 loading → error → empty 的优先级选择异步区域的可见状态。
 * 成功路径直接渲染 children，不加额外包裹以保持布局由页面控制。
 */
export function AsyncView({ loading, error, empty, emptyText = '暂无数据', onRetry, children }: Props) {
  if (loading) return <div className="state-view"><LoaderCircle className="spin" /><span>正在加载</span></div>
  if (error) return <div className="state-view error"><AlertCircle /><strong>加载失败</strong><span>{error}</span>{onRetry && <button onClick={onRetry}><RefreshCw size={14} />重试</button>}</div>
  if (empty) return <div className="state-view"><Inbox /><span>{emptyText}</span></div>
  return <>{children}</>
}
