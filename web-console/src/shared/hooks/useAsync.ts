/**
 * 为数据加载页面提供统一的 loading、error、data 与手动重载状态。
 *
 * Hook 职责边界：
 * - 只负责一次性 Promise 生命周期（mount 时自动加载 + reload 手动刷新）
 * - 不提供请求取消、竞态消解（后发先至）、轮询或 SSE 实时更新
 * - 错误信息仅保留 cause.message，不暴露 HTTP status 等结构化细节（页面需自行 catch API 若需）
 *
 * 副作用：
 * - useEffect 依赖 reload；reload 依赖由调用方传入的 dependencies
 * - 依赖变更会创建新 reload 函数并触发重新请求（可能重复 hit 后端）
 */
import { useCallback, useEffect, useState } from 'react'

/** useAsync 对外暴露的稳定状态快照。 */
export interface AsyncState<T> {
  data: T | null
  loading: boolean
  error: string | null
  reload: () => Promise<void>
}

/**
 * 执行异步加载器，并把成功结果或可展示错误收敛为稳定页面状态。
 *
 * @param loader 返回 Promise 的数据获取函数（通常封装 api.get 等）
 * @param dependencies 传入 useCallback 的依赖数组；变更时重新创建 reload 并触发 useEffect 重载
 */
export function useAsync<T>(loader: () => Promise<T>, dependencies: unknown[] = []): AsyncState<T> {
  const [data, setData] = useState<T | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const reload = useCallback(async () => {
    // 每次重载先进入 loading 并清空旧错误，避免 UI 同时展示 stale data 与 error。
    setLoading(true)
    setError(null)
    try {
      setData(await loader())
    } catch (cause) {
      // 非 Error 实例统一映射为「未知错误」，避免渲染 [object Object]。
      setError(cause instanceof Error ? cause.message : '未知错误')
    } finally {
      setLoading(false)
    }
  // 依赖由调用方显式声明，避免 loader 函数每次渲染都导致重复请求。
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, dependencies)

  // mount 与 reload 引用变化时自动拉取；void 忽略 Promise 避免 eslint floating promise 警告。
  useEffect(() => { void reload() }, [reload])
  return { data, loading, error, reload }
}
