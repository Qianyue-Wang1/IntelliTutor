import { render, screen } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { expect, test, vi } from 'vitest'

vi.mock('@/providers/AuthProvider', () => ({ useAuth: () => ({ isAuthenticated: false }) }))

import ProtectedRoute from './ProtectedRoute'

test('sends an unauthenticated local participant to the name-and-phone entry page', async () => {
  render(<MemoryRouter initialEntries={['/experiment/records']}><Routes>
    <Route element={<ProtectedRoute />}><Route path="/experiment/records" element={<div>记录页</div>} /></Route>
    <Route path="/" element={<div>姓名和手机号入口</div>} />
    <Route path="/login" element={<div>旧登录页</div>} />
  </Routes></MemoryRouter>)

  expect(await screen.findByText('姓名和手机号入口')).toBeInTheDocument()
})
