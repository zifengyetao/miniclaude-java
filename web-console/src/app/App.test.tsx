import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import App from './App'

describe('App navigation', () => {
  beforeEach(() => {
    vi.spyOn(globalThis, 'fetch').mockResolvedValue(new Response('[]', {
      status: 200,
      headers: { 'Content-Type': 'application/json' },
    }))
  })

  it('renders backend-driven dashboard and navigates to Graph Studio', async () => {
    render(<App />)
    await waitFor(() => expect(screen.getByRole('heading', { name: '平台工作台' })).toBeInTheDocument())
    await userEvent.click(screen.getByRole('link', { name: 'Graph Studio' }))
    expect(screen.getByRole('heading', { name: 'Graph Studio' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: /验证图定义/ })).toBeInTheDocument()
  })
})
