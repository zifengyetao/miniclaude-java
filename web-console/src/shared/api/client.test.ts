/** 验证统一 API 客户端的会话凭据请求头和类型化错误转换。 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api, credentials } from './client'

describe('ApiClient', () => {
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('reads credentials from sessionStorage and sends platform headers', async () => {
    credentials.save('dev-secret', 'tenant-a', 'operator-a')
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }))

    await api.get<{ ok: boolean }>('/health')

    const headers = new Headers(fetchMock.mock.calls[0][1]?.headers)
    expect(headers.get('X-Platform-Api-Key')).toBe('dev-secret')
    expect(headers.get('X-Tenant-Id')).toBe('tenant-a')
    expect(headers.get('X-Actor-Id')).toBe('operator-a')
  })

  it('throws a typed error for failed requests', async () => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ message: 'denied' }), { status: 401 }))
    await expect(api.get('/protected')).rejects.toMatchObject({ status: 401, message: 'denied' })
  })
})
