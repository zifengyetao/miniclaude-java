/**
 * ApiClient 与 credentials 的单元测试。
 *
 * 测什么：
 * - sessionStorage 中的开发凭据是否正确注入到 fetch 请求头
 * - 非 2xx 响应是否转换为带 status 的 ApiError
 *
 * 为何 mock fetch：避免测试依赖真实后端；断言聚焦客户端契约而非 Spring 鉴权逻辑。
 */
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { api, credentials } from './client'

describe('ApiClient', () => {
  /** 每个用例前清空 sessionStorage 并恢复 mock，防止用例间凭据泄漏。 */
  beforeEach(() => {
    sessionStorage.clear()
    vi.restoreAllMocks()
  })

  it('reads credentials from sessionStorage and sends platform headers', async () => {
    // 模拟用户在 AppShell 中保存的 API Key / 租户 / 操作者。
    credentials.save('dev-secret', 'tenant-a', 'operator-a')
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ ok: true }), {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }))

    await api.get<{ ok: boolean }>('/health')

    // 断言：三个平台头必须与 sessionStorage 一致，供后端 ApiKeyFilter 与租户上下文读取。
    const headers = new Headers(fetchMock.mock.calls[0][1]?.headers)
    expect(headers.get('X-Platform-Api-Key')).toBe('dev-secret')
    expect(headers.get('X-Tenant-Id')).toBe('tenant-a')
    expect(headers.get('X-Actor-Id')).toBe('operator-a')
  })

  it('throws a typed error for failed requests', async () => {
    // 401 时后端返回 JSON message；客户端应抛出 ApiError 而非裸 Error。
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response(JSON.stringify({ message: 'denied' }), { status: 401 }))
    await expect(api.get('/protected')).rejects.toMatchObject({ status: 401, message: 'denied' })
  })
})
