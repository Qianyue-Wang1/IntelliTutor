import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter, Route, Routes } from 'react-router'
import { expect, test, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  registrationOptions: vi.fn().mockResolvedValue({ methods: ['SMS_VERIFIED'] }),
  sendRegistrationSms: vi.fn().mockResolvedValue(null),
  registerExperiment: vi.fn().mockResolvedValue({ login_account: '13800138000' }),
  notice: vi.fn(),
}))

vi.mock('@/api/services', () => ({ services: {
  experimentRegistrationOptions: mocks.registrationOptions,
  sendExperimentRegistrationSms: mocks.sendRegistrationSms,
  registerExperiment: mocks.registerExperiment,
} }))
vi.mock('@/hooks/useCapabilities', () => ({
  useCapabilities: () => ({ capabilities: { experiment_mode_enabled: true } }),
}))
vi.mock('@/providers/AuthProvider', () => ({
  useAuth: () => ({ isAuthenticated: false, user: null, login: vi.fn() }),
}))
vi.mock('@/providers/NoticeProvider', () => ({ useNotice: () => mocks.notice }))

import AuthPage from './AuthPage'

test('machine-learning registration shows only enabled methods and sends the selected SMS proof', async () => {
  const user = userEvent.setup()
  render(<MemoryRouter initialEntries={['/register']}><Routes>
    <Route path="/register" element={<AuthPage kind="register" />} />
    <Route path="/login" element={<p>登录页</p>} />
  </Routes></MemoryRouter>)

  expect(await screen.findByLabelText('姓名')).toBeInTheDocument()
  expect(screen.getByLabelText('短信验证码')).toBeInTheDocument()
  expect(screen.queryByLabelText('实验邀请码')).not.toBeInTheDocument()

  await user.type(screen.getByLabelText('姓名'), '张三')
  await user.type(screen.getByLabelText('手机号'), '13800138000')
  await user.type(screen.getByLabelText('密码'), 'safe-password')
  await user.click(screen.getByRole('button', { name: '获取验证码' }))
  expect(mocks.sendRegistrationSms).toHaveBeenCalledTimes(1)

  await user.type(screen.getByLabelText('短信验证码'), '123456')
  await user.click(screen.getByRole('button', { name: '报名机器学习实验' }))
  expect(mocks.registerExperiment).toHaveBeenCalledWith({
    name: '张三', phone_number: '13800138000', password: 'safe-password', method: 'SMS_VERIFIED', proof: '123456',
  })
})

test('machine-learning registration submits an invite code without requesting an SMS', async () => {
  mocks.sendRegistrationSms.mockClear()
  mocks.registerExperiment.mockClear()
  mocks.registrationOptions.mockResolvedValueOnce({ methods: ['SMS_VERIFIED', 'INVITE_CODE'] })
  const user = userEvent.setup()
  render(<MemoryRouter initialEntries={['/register']}><Routes>
    <Route path="/register" element={<AuthPage kind="register" />} />
    <Route path="/login" element={<p>登录页</p>} />
  </Routes></MemoryRouter>)

  await screen.findByLabelText('实验邀请码')
  await user.click(screen.getByLabelText('实验邀请码'))
  await user.type(screen.getByLabelText('姓名'), '李四')
  await user.type(screen.getByLabelText('手机号'), '13900139000')
  await user.type(screen.getByLabelText('密码'), 'safe-password')
  await user.type(screen.getByPlaceholderText('邀请码'), 'ABCDE23456')
  await user.click(screen.getByRole('button', { name: '报名机器学习实验' }))

  expect(mocks.sendRegistrationSms).not.toHaveBeenCalled()
  expect(mocks.registerExperiment).toHaveBeenCalledWith({
    name: '李四', phone_number: '13900139000', password: 'safe-password', method: 'INVITE_CODE', proof: 'ABCDE23456',
  })
})
