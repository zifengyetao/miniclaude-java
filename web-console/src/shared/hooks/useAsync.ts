import { useCallback, useEffect, useState } from 'react'

export interface AsyncState<T> {
  data: T | null
  loading: boolean
  error: string | null
  reload: () => Promise<void>
}

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
  // Dependencies are explicitly controlled by callers.
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, dependencies)

  useEffect(() => { void reload() }, [reload])
  return { data, loading, error, reload }
}
