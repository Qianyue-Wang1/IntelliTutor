import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, test, vi } from 'vitest'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'

const mocks = vi.hoisted(() => ({
  navigate: vi.fn(),
  notice: vi.fn(),
  refresh: vi.fn(),
  tutorStart: vi.fn(),
  selectExperimentMode: vi.fn(),
  noAiLecture: vi.fn(),
  noAiScroll: vi.fn(),
  noAiComplete: vi.fn(),
  directSession: vi.fn(),
  directFollowup: vi.fn(),
  experimentCredential: vi.fn(),
  credentialPng: vi.fn(),
  updateUser: vi.fn(),
}))

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return { ...actual, useNavigate: () => mocks.navigate, useParams: () => ({}) }
})
vi.mock('@/api/services', () => ({ services: {
  tutorStart: mocks.tutorStart,
  selectExperimentMode: mocks.selectExperimentMode,
  noAiLecture: mocks.noAiLecture,
  noAiScroll: mocks.noAiScroll,
  noAiComplete: mocks.noAiComplete,
  directSession: mocks.directSession,
  directFollowup: mocks.directFollowup,
  experimentCredential: mocks.experimentCredential,
  credentialPng: mocks.credentialPng,
  experimentPassword: vi.fn(),
} }))
vi.mock('@/providers/ExperimentProvider', () => ({
  useExperiment: () => ({
    context: { experiment_mode: true, assigned: true, global_stage: 'ROUND_3_TUTOR', group_code: 'BASIC_TUTOR', state_version: 2 },
    loading: false,
    refresh: mocks.refresh,
  }),
}))
vi.mock('@/providers/AuthProvider', () => ({
  useAuth: () => ({ updateUser: mocks.updateUser, user: null }),
}))
vi.mock('@/providers/NoticeProvider', () => ({ useNotice: () => mocks.notice }))
vi.mock('./telemetry', () => ({ useExperimentTelemetry: vi.fn() }))

import { CompletionCredentialPage, DirectTutorPage, ExperimentPasswordPage, ModeSelectionPage, NoAiLearningPage, TutorLaunchPage, TutorModePage } from './ExperimentPages'

beforeEach(() => {
  vi.clearAllMocks()
  mocks.refresh.mockResolvedValue({ data: { next_route: '/ai-tutor/explanation/session-3' } })
  mocks.tutorStart.mockResolvedValue({
    run_id: 'run-3', group_code: 'BASIC_TUTOR', round: 3, mode: 'explanation',
    session_id: 'session-3', resumed: false, route: '/ai-tutor/explanation/session-3',
  })
  mocks.selectExperimentMode.mockResolvedValue({ next_route: '/experiment/tutor/start' })
  mocks.noAiLecture.mockResolvedValue({ round: 1, markdown: '固定学习讲义', bottom_reached: false })
  mocks.noAiScroll.mockResolvedValue(undefined)
  mocks.noAiComplete.mockResolvedValue(undefined)
  mocks.experimentCredential.mockResolvedValue({
    id: 'credential-1', credential_no: 'EXP-ABC', participant_name: '王小明', participant_code: 'S001',
    topic: '从无监督学习到大模型自监督预训练', selected_mode: 'FULL_TUTOR', issued_at: '2026-10-11T09:00:00Z',
  })
  mocks.credentialPng.mockResolvedValue(new Blob(['credential'], { type: 'image/png' }))
  mocks.directSession.mockResolvedValue({
    session_id: 'session-3', question: '仅供模型使用的内部提示词', display_question: '无监督学习中的聚类、降维与异常检测', initial_explanation_generated: true,
    messages: [
      { role: 'assistant', kind: 'initial_explanation', content: '讲解', time: '1' },
      { role: 'user', kind: 'followup', content: '问题一', time: '2' },
      { role: 'assistant', kind: 'followup_reply', content: '回答一', time: '2' },
      { role: 'user', kind: 'followup', content: '问题二', time: '3' },
      { role: 'assistant', kind: 'followup_reply', content: '回答二', time: '3' },
      { role: 'user', kind: 'followup', content: '问题三', time: '4' },
      { role: 'assistant', kind: 'followup_reply', content: '回答三', time: '4' },
    ],
  })
})

test('refreshes experiment context before leaving the automatic Tutor launch page', async () => {
  render(<TutorLaunchPage />)

  await waitFor(() => expect(mocks.navigate).toHaveBeenCalledWith('/ai-tutor/explanation/session-3', { replace: true }))
  expect(mocks.refresh).toHaveBeenCalledOnce()
  expect(mocks.refresh.mock.invocationCallOrder[0]).toBeLessThan(mocks.navigate.mock.invocationCallOrder[0])
})

test('refreshes experiment context after choosing a structured Tutor mode', async () => {
  render(<TutorModePage />)
  fireEvent.click(screen.getByRole('button', { name: /逐步讲解/ }))

  await waitFor(() => expect(mocks.navigate).toHaveBeenCalledWith('/ai-tutor/explanation/session-3', { replace: true }))
  expect(mocks.tutorStart).toHaveBeenCalledWith('explanation')
  expect(mocks.refresh.mock.invocationCallOrder[0]).toBeLessThan(mocks.navigate.mock.invocationCallOrder[0])
})

test('mandatory password fields independently toggle visibility', () => {
  render(<ExperimentPasswordPage />)
  const current = screen.getByLabelText('当前初始密码')
  const next = screen.getByLabelText('新密码')

  expect(current).toHaveAttribute('type', 'password')
  expect(next).toHaveAttribute('type', 'password')
  fireEvent.click(screen.getByRole('button', { name: '显示当前初始密码' }))

  expect(current).toHaveAttribute('type', 'text')
  expect(next).toHaveAttribute('type', 'password')
  expect(screen.getByRole('button', { name: '隐藏当前初始密码' })).toHaveAttribute('aria-pressed', 'true')
})

test('offers the four fixed experiment modes and submits an explicit selected mode', async () => {
  render(<ModeSelectionPage />)

  expect(screen.getByText('实验设置')).toBeInTheDocument()
  expect(screen.queryByText('前测完成')).not.toBeInTheDocument()
  expect(screen.getByText('根据本轮学习过程调整讲解细致程度。')).toBeInTheDocument()
  expect(screen.getByText('从第二轮起结合此前轮次的结构化学习状态。')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: /Direct LLM/ })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: /Basic Tutor/ })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: /Full Tutor/ })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: /无 AI 对照/ })).toBeInTheDocument()
  fireEvent.click(screen.getByRole('button', { name: /Direct LLM/ }))
  fireEvent.click(screen.getByRole('button', { name: '确认选择 Direct LLM' }))

  await waitFor(() => expect(mocks.selectExperimentMode).toHaveBeenCalledWith('DIRECT_LLM', 2))
})

test('requires the no-AI lecture to reach its bottom before continuing to the quiz', async () => {
  render(<NoAiLearningPage />)

  const lecture = await screen.findByRole('region', { name: '无 AI 讲义正文' })
  const complete = screen.getByRole('button', { name: '完成学习并进入小后测' })
  expect(complete).toBeDisabled()
  Object.defineProperties(lecture, {
    scrollTop: { value: 996 }, clientHeight: { value: 100 }, scrollHeight: { value: 1100 },
  })
  fireEvent.scroll(lecture)

  await waitFor(() => expect(mocks.noAiScroll).toHaveBeenCalledWith(100))
  expect(complete).toBeEnabled()
  fireEvent.click(complete)
  await waitFor(() => expect(mocks.noAiComplete).toHaveBeenCalledOnce())
})

test('records a no-AI lecture as read when its full content already fits on screen', async () => {
  const clientHeight = vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(600)
  const scrollHeight = vi.spyOn(HTMLElement.prototype, 'scrollHeight', 'get').mockReturnValue(580)

  try {
    render(<NoAiLearningPage />)

    await screen.findByRole('region', { name: '无 AI 讲义正文' })
    await waitFor(() => expect(mocks.noAiScroll).toHaveBeenCalledWith(100))
    expect(screen.getByRole('button', { name: '完成学习并进入小后测' })).toBeEnabled()
  } finally {
    clientHeight.mockRestore()
    scrollHeight.mockRestore()
  }
})

test('removes Direct LLM follow-up input after the third follow-up', async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><DirectTutorPage /></QueryClientProvider>)

  await screen.findByText('已完成三次追问，正在进入本轮小后测。')
  expect(screen.queryByRole('button', { name: '发送追问' })).not.toBeInTheDocument()
  await waitFor(() => expect(mocks.navigate).toHaveBeenCalledWith('/ai-tutor/explanation/session-3', { replace: true }))
})

test('shows the Direct LLM learning topic without exposing its internal prompt', async () => {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><DirectTutorPage /></QueryClientProvider>)

  expect(await screen.findByText('本轮学习主题')).toBeInTheDocument()
  expect(screen.getByText('无监督学习中的聚类、降维与异常检测')).toBeInTheDocument()
  expect(screen.queryByText('仅供模型使用的内部提示词')).not.toBeInTheDocument()
})

test('renders an issued credential without scores or phone number', async () => {
  render(<CompletionCredentialPage />)

  expect(await screen.findByText('你已完成本次测试')).toBeInTheDocument()
  expect(screen.getByText('王小明')).toBeInTheDocument()
  expect(screen.getByText('EXP-ABC')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: '保存完成凭证' })).toBeInTheDocument()
  expect(screen.queryByText(/得分|手机号/)).not.toBeInTheDocument()
})

test('downloads the issued credential as a PNG', async () => {
  Object.defineProperty(URL, 'createObjectURL', { value: vi.fn(() => 'blob:credential'), configurable: true })
  Object.defineProperty(URL, 'revokeObjectURL', { value: vi.fn(), configurable: true })
  const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => undefined)
  render(<CompletionCredentialPage />)

  await screen.findByText('EXP-ABC')
  fireEvent.click(screen.getByRole('button', { name: '保存完成凭证' }))

  await waitFor(() => expect(mocks.credentialPng).toHaveBeenCalledWith('credential-1'))
  expect(click).toHaveBeenCalledOnce()
  click.mockRestore()
})
