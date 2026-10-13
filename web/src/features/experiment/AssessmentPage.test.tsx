import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { beforeEach, expect, test, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  assessmentStart: vi.fn(),
  questionViewed: vi.fn(),
  saveAnswer: vi.fn(),
  notice: vi.fn(),
  refresh: vi.fn(),
}))

vi.mock('react-router', async () => {
  const actual = await vi.importActual<typeof import('react-router')>('react-router')
  return { ...actual, useNavigate: () => vi.fn(), useParams: () => ({ stage: 'pretest' }) }
})
vi.mock('@/api/services', () => ({ services: {
  assessmentStart: mocks.assessmentStart,
  assessment: vi.fn(),
  questionViewed: mocks.questionViewed,
  saveAnswer: mocks.saveAnswer,
  submitAssessment: vi.fn(),
} }))
vi.mock('@/providers/ExperimentProvider', () => ({
  useExperiment: () => ({
    context: { experiment_mode: true, assigned: true, global_stage: 'PRETEST', attempt_id: null },
    loading: false,
    refresh: mocks.refresh,
  }),
}))
vi.mock('@/providers/AuthProvider', () => ({ useAuth: () => ({ user: null }) }))
vi.mock('@/providers/NoticeProvider', () => ({ useNotice: () => mocks.notice }))
vi.mock('./telemetry', () => ({ useExperimentTelemetry: vi.fn() }))

import { AssessmentPage } from './ExperimentPages'

function renderAssessment() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(<QueryClientProvider client={client}><AssessmentPage /></QueryClientProvider>)
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.questionViewed.mockResolvedValue(null)
  mocks.saveAnswer.mockResolvedValue({ question_id: 'question-1', answer: 'B', revision: 1, change_count: 0 })
})

test('saves one scalar option for a single-choice assessment question', async () => {
  mocks.assessmentStart.mockResolvedValue({
    attempt_id: 'attempt-1', code: 'PRETEST', title: '前测', status: 'IN_PROGRESS', started_at: '2026-09-08T08:00:00+08:00', answers: [],
    questions: [{ id: 'question-1', code: 'PRE_Q1', type: 'SINGLE_CHOICE', prompt: '请选择正确答案', options: { A: '选项一', B: '选项二' }, sequence_no: 1 }],
  })
  renderAssessment()

  fireEvent.click(await screen.findByRole('radio', { name: /B.*选项二/ }))

  await waitFor(() => expect(mocks.saveAnswer).toHaveBeenCalledWith('attempt-1', 'question-1', 'B', 0, expect.any(String)))
})

test('blocks an accidentally imported multiple-choice question', async () => {
  mocks.assessmentStart.mockResolvedValue({
    attempt_id: 'attempt-1', code: 'PRETEST', title: '前测', status: 'IN_PROGRESS', started_at: '2026-09-08T08:00:00+08:00', answers: [],
    questions: [{ id: 'question-1', code: 'PRE_Q1', type: 'MULTIPLE_CHOICE', prompt: '误配置题目', options: { A: '选项一', B: '选项二' }, sequence_no: 1 }],
  })
  renderAssessment()

  expect(await screen.findByRole('alert')).toHaveTextContent('当前实验只支持单选题')
  expect(screen.queryByRole('radiogroup')).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: '提交整份测试' })).toBeDisabled()
})
