import { fireEvent, render, screen, within } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { expect, test, vi } from 'vitest'
import AppShell from './AppShell'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

const mocks = vi.hoisted(() => ({
  experimentMode: false,
  logout: vi.fn(),
  user: null as { id: string, roles?: string[] } | null,
}))

vi.mock('@/hooks/useCapabilities', () => ({
  useCapabilities: () => ({ capabilities: { experiment_mode_enabled: mocks.experimentMode } }),
}))
vi.mock('@/providers/AuthProvider', () => ({
  useAuth: () => ({ user: mocks.user, isAuthenticated: Boolean(mocks.user), logout: mocks.logout }),
}))

function Location() {
  return <p>{useLocation().pathname}</p>
}

test('top bar only exposes the three retained navigation items', () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  mocks.experimentMode = false
  mocks.user = null
  render(<QueryClientProvider client={client}><MemoryRouter><Routes><Route element={<AppShell />}><Route index element={<p>home</p>} /></Route></Routes></MemoryRouter></QueryClientProvider>)
  const navigation = screen.getByRole('navigation', { name: '主导航' })
  const links = within(navigation).getAllByRole('link')
  expect(links).toHaveLength(3)
  expect(links.map(link => link.textContent)).toEqual(['首页', 'AI 导师', '我的'])
})

test('an authenticated experiment user can log out and return to login', () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  mocks.experimentMode = true
  mocks.user = { id: 'admin-1', roles: ['ADMIN'] }
  mocks.logout.mockClear()
  render(<QueryClientProvider client={client}><MemoryRouter initialEntries={['/admin/experiment']}><Routes><Route element={<AppShell />}><Route path="*" element={<Location />} /></Route></Routes></MemoryRouter></QueryClientProvider>)

  fireEvent.click(screen.getByRole('button', { name: '退出登录' }))

  expect(mocks.logout).toHaveBeenCalledOnce()
  expect(screen.getByText('/login')).toBeInTheDocument()
})
