import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { expect, test, vi } from 'vitest'

const mocks = vi.hoisted(() => ({ navigate: vi.fn(), login: vi.fn(), start: vi.fn() }))

vi.mock('react-router', async () => ({ ...(await vi.importActual<typeof import('react-router')>('react-router')), useNavigate: () => mocks.navigate }))
vi.mock('@/api/services', () => ({ services: { startLocalExperiment: mocks.start } }))
vi.mock('@/providers/AuthProvider', () => ({ useAuth: () => ({ login: mocks.login, isAuthenticated: false }) }))

import LocalExperimentStartPage from './LocalExperimentStartPage'

test('opens history records with a recovered name-and-phone participant identity', async () => {
  mocks.start.mockResolvedValue({ access_token: 'access', refresh_token: 'refresh', user: { id: 'participant-1' } })
  render(<LocalExperimentStartPage />)

  fireEvent.change(screen.getByLabelText('姓名'), { target: { value: '陈' } })
  fireEvent.change(screen.getByLabelText('手机号'), { target: { value: '18928808216' } })
  fireEvent.click(screen.getByRole('button', { name: '查看历史记录' }))

  await waitFor(() => expect(mocks.start).toHaveBeenCalledWith('陈', '18928808216'))
  expect(mocks.login).toHaveBeenCalledOnce()
  expect(mocks.navigate).toHaveBeenCalledWith('/experiment/records', { replace: true })
})

test('describes the no-pretest local experiment flow', () => {
  render(<LocalExperimentStartPage />)

  expect(screen.getByText(/模式选择、五轮学习和后测/)).toBeInTheDocument()
  expect(screen.queryByText(/前测/)).not.toBeInTheDocument()
})
