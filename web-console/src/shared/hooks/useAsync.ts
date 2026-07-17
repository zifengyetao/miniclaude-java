/**
 * 为数据加载页面提供统一的 loading、error、data 与手动重载状态。
 * Hook 只负责一次性 Promise 生命周期，不提供请求取消、竞态消解、轮询或 SSE 实时更新。
 */
import { useCallback, useEffect, useState } from 'react'

export interface AsyncState<T> {
  data: T | null
  loading: boolean
  error: string | null
  reload: () => Promise<void>
}

/**
 * 执行异步加载器，并把成功结果或可展示错误收敛为稳定页面状态。
 * 依赖数组由调用方显式控制；变更依赖会创建新的 reload 并触发重新加载。
 */
export function useAsync<T>(loader: () => Promise<T>, dependencies: unknown[] = []): AsyncState<T> {
  const [data, setData] = useState<T | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const reload = useCallback(async () => {
    setLoading(true)
    setError(null)
    try {
      setData(await loader())
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : '未知错误')
    } finally {
      setLoading(false)
    }
  // 依赖由调用方显式声明，避免 loader 函数每次渲染都导致重复请求。
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, dependencies)

  useEffect(() => { void reload() }, [reload])
  return { data, loading, error, reload }
}
