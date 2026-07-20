/**
 * App 路由与外壳集成测试（Vitest + Testing Library）。
 *
 * 测什么：
 * - 默认路由是否渲染工作台标题（证明 Dashboard 与 useAsync 链路可工作）
 * - 点击侧栏 Graph Studio 链接是否切换路由并展示校验按钮
 *
 * 为何 mock fetch 返回 []：Dashboard 等页面 mount 时会并行请求 agents/runs/assets；
 * 空数组足以让 AsyncView 进入成功态，无需构造完整 JSON fixture。
 */
import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'

describe('App navigation', () => {
  /** 全局 stub fetch，避免测试环境无后端时网络失败。 */
  beforeEach(() => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('[]', {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }))
  })

  it('renders backend-driven dashboard and navigates to Graph Studio', async () => {
    render(<App />)
    // waitFor：Dashboard 异步加载完成后才应出现 h1。
    await waitFor(() => expect(screen.getByRole('heading', { name: '平台工作台' })).toBeInTheDocument())
    // 通过可访问名称点击 NavLink，验证 client-side routing 而非 full page reload。
    await userEvent.click(screen.getByRole('link', { name: 'Graph Studio' }))
    expect(screen.getByRole('heading', { name: 'Graph Studio' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /验证图定义/ })).toBeInTheDocument()
  })
})
