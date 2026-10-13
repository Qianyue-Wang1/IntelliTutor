import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes, useLocation } from 'react-router'
import { expect, test, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  userInfo: vi.fn().mockResolvedValue({ nickname: '学习者', email: 'learner@example.com' }),
  sessions: vi.fn().mockResolvedValue([{
    session_id: 'finished-session',
    question: '回看 PCA 学习过程',
    type: 'explanation',
    created_at: '2026-09-30T10:00:00',
    last_accessed: '2026-09-30T11:00:00',
  }]),
  deleteSession: vi.fn(),
}))

vi.mock('@/api/services', () => ({ services: {
  userInfo: mocks.userInfo,
  sessions: mocks.sessions,
  deleteSession: mocks.deleteSession,
} }))
vi.mock('@/providers/AuthProvider', () => ({ useAuth: () => ({ logout: vi.fn(), updateUser: vi.fn() }) }))
vi.mock('@/providers/NoticeProvider', () => ({ useNotice: () => vi.fn() }))

import ProfilePage from './ProfilePage'

function ReviewDestination() {
  const location = useLocation()
  return <div>目标地址：{location.pathname}{location.search}</div>
}

test('opens an explanation history session in explicit review mode', async () => {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/profile']}>
        <Routes>
          <Route path="/profile" element={<ProfilePage />} />
          <Route path="/ai-tutor/explanation/:sessionId" element={<ReviewDestination />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )

  fireEvent.click(screen.getByRole('button', { name: '学习会话' }))
  fireEvent.click(await screen.findByRole('button', { name: /回看 PCA 学习过程/ }))

  expect(await screen.findByText('目标地址：/ai-tutor/explanation/finished-session?review=1')).toBeInTheDocument()
})
