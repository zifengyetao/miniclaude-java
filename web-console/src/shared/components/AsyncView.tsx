import { AlertCircle, Inbox, LoaderCircle, RefreshCw } from 'lucide-react'
import type { ReactNode } from 'react'

interface Props {
  loading: boolean
  error: string | null
  empty?: boolean
  emptyText?: string
  onRetry?: () => void
  children: ReactNode
}

export function AsyncView({ loading, error, empty, emptyText = '暂无数据', onRetry, children }: Props) {
  if (loading) return <div className="state-view"><LoaderCircle className="spin" /><span>正在加载</span></div>
  if (error) return <div className="state-view error"><AlertCircle /><strong>加载失败</strong><span>{error}</span>{onRetry && <button onClick={onRetry}><RefreshCw size={14} />重试</button>}</div>
  if (empty) return <div className="state-view"><Inbox /><span>{emptyText}</span></div>
  return <>{children}</>
}
