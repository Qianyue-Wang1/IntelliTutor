import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, expect, test, vi } from 'vitest'
import { ApiError } from '@/api/client'
import type { ExplanationSession } from '@/api/types'

const mocks = vi.hoisted(() => ({
  explanation: vi.fn(),
  explanationStep: vi.fn(),
  answerStep: vi.fn(),
  advanceExplanation: vi.fn(),
  updateExplanation: vi.fn(),
  completeTutor: vi.fn(),
  refresh: vi.fn(),
  notify: vi.fn(),
  context: { experiment_mode: true, assigned: true },
}))

vi.mock('@/api/services', () => ({ services: {
  explanation: mocks.explanation,
  explanationStep: mocks.explanationStep,
  answerStep: mocks.answerStep,
  advanceExplanation: mocks.advanceExplanation,
  updateExplanation: mocks.updateExplanation,
  completeTutor: mocks.completeTutor,
} }))
vi.mock('@/providers/AuthProvider', () => ({ useAuth: () => ({ user: { id: 'student-1' } }) }))
vi.mock('@/providers/ExperimentProvider', () => ({
  useExperiment: () => ({ context: mocks.context, refresh: mocks.refresh }),
}))
vi.mock('@/providers/NoticeProvider', () => ({ useNotice: () => mocks.notify }))
vi.mock('@/features/experiment/telemetry', () => ({ useExperimentTelemetry: vi.fn() }))

import ExplanationPage from './ExplanationPage'

function explanationSession(personalized: boolean): ExplanationSession {
  return {
    session_id: 'session-1',
    question: '1 + 1 = ?',
    personalized_mode: personalized,
    adaptive_mode: personalized,
    cross_session_enabled: false,
    persist_learner_state: personalized,
    user_id: 'student-1',
    current_step: 1,
    total_steps: 1,
    in_summary: false,
    knowledge_points: [],
    wrong_answers: [],
    steps_info: [{
      step_number: 1,
      analysis: '计算加法',
      operation_prefix: '求和',
      options: ['2', '3'],
      operation_summary_list: [],
    }],
    steps_dict: {},
    steps_op_prefix_dic: {},
  }
}

function renderPage(session: ExplanationSession, answerResult: Record<string, unknown> = {
  session_id: session.session_id,
  question: session.question,
  current_step: session.current_step,
  total_steps: session.total_steps,
  in_summary: session.in_summary,
  knowledge_points: [],
  wrong_answers: [],
  error_analysis: 'True',
  is_correct: true,
  selected_option: 'A',
  correct_option: 'A',
  answer_explanation: '加法表示把两个数量合并，因此答案是 2。',
  awaiting_next: true,
}, initialEntry = '/ai-tutor/explanation/session-1') {
  mocks.explanation.mockResolvedValue(session)
  mocks.answerStep.mockResolvedValue(answerResult)
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[initialEntry]}>
        <Routes>
          <Route path="/ai-tutor/explanation/:sessionId" element={<ExplanationPage />} />
          <Route path="/experiment/waiting" element={<div>实验下一任务</div>} />
          <Route path="/ai-tutor" element={<div>自由提问首页</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

async function submitFirstOption() {
  const optionText = await screen.findByText('2')
  fireEvent.click(optionText.closest('button')!)
  fireEvent.click(screen.getByRole('button', { name: /提交检验/ }))
  await waitFor(() => expect(mocks.answerStep).toHaveBeenCalledOnce())
}

beforeEach(() => {
  vi.clearAllMocks()
  mocks.context.experiment_mode = true
  mocks.context.assigned = true
  mocks.refresh.mockResolvedValue({ data: { next_route: '/experiment/waiting' } })
  mocks.completeTutor.mockResolvedValue(undefined)
  mocks.updateExplanation.mockResolvedValue(undefined)
  mocks.advanceExplanation.mockResolvedValue(explanationSession(false))
})

test('shows answer feedback and waits for an explicit next-step action', async () => {
  const session = explanationSession(false)
  const next = { ...session, current_step: 2, total_steps: 2, steps_info: session.steps_info }
  mocks.advanceExplanation.mockResolvedValue(next)
  renderPage(session)

  await submitFirstOption()

  expect(screen.getByText('掌握检验')).toBeInTheDocument()
  expect(screen.getByText('回答正确')).toBeInTheDocument()
  expect(screen.getByText(/加法表示把两个数量合并/)).toBeInTheDocument()
  expect(mocks.advanceExplanation).not.toHaveBeenCalled()

  fireEvent.click(screen.getByRole('button', { name: /进入下一步/ }))
  await waitFor(() => expect(mocks.advanceExplanation).toHaveBeenCalledWith('session-1'))
})

test('Full Explanation submits behavior data using the authenticated user', async () => {
  renderPage(explanationSession(true))

  await submitFirstOption()

  expect(mocks.answerStep).toHaveBeenCalledWith(
    'session-1',
    'A',
    expect.objectContaining({
      session_meta: {
        user_id: 'student-1',
        session_id: 'session-1',
        current_mode: 'explanation_mode',
        current_step_id: '1',
      },
      event_stream: expect.arrayContaining([
        expect.objectContaining({
          event_type: 'step_submit',
          payload: expect.objectContaining({ submitted_content: 'A' }),
        }),
      ]),
    }),
    '1',
  )
})

test('Basic Explanation omits behavior data', async () => {
  renderPage(explanationSession(false))

  await submitFirstOption()

  expect(mocks.answerStep).toHaveBeenCalledWith('session-1', 'A', undefined, '1')
})

test('does not render a learning-process summary card alongside the final teaching step', async () => {
  const session = explanationSession(false)
  session.current_step = 4
  session.total_steps = 4
  session.in_summary = true
  session.steps_info = session.steps_info.map(step => ({ ...step, user_selected_option: 'A' }))
  session.steps_history = [{
    step_number: 4,
    analysis: '回顾全部解题过程并选择正确总结',
    operation_prefix: '最终确定解并声明使用的性质',
    options: ['错误总结', '正确总结'],
    operation_summary_list: ['步骤一', '步骤二'],
  }]

  renderPage(session)

  expect(await screen.findByText('回顾全部解题过程并选择正确总结')).toBeInTheDocument()
  expect(screen.getByText(/总结 · 步骤 4/)).toBeInTheDocument()
  expect(screen.queryByRole('region', { name: '学习过程总结' })).not.toBeInTheDocument()
  expect(mocks.explanationStep).not.toHaveBeenCalled()
})

test('keeps legacy summary data out of the final teaching step', async () => {
  const session = explanationSession(false)
  session.current_step = 2
  session.total_steps = 2
  session.in_summary = true
  session.steps_history = [{
    step_number: 2,
    analysis: '总结分析提示',
    operation_prefix: '确认答案',
    options: ['正确', '错误'],
    operation_summary_list: [],
  }]
  session.steps_data = { 步骤2: { 操作总结: '一、移项；二、化简；三、验证。' } }

  renderPage(session)

  expect(await screen.findByText('总结分析提示')).toBeInTheDocument()
  expect(screen.queryByRole('region', { name: '学习过程总结' })).not.toBeInTheDocument()
})

test('merges a fetched summary step into an initially loaded session', async () => {
  const session = explanationSession(false)
  session.current_step = 2
  session.total_steps = 2
  session.in_summary = true
  session.steps_info = session.steps_info.map(step => ({ ...step, user_selected_option: 'A' }))
  session.steps_history = []
  mocks.explanationStep.mockResolvedValue({
    step_number: 2,
    analysis: '刚刚生成的总结步骤',
    operation_prefix: '总结方法',
    options: ['总结 A', '总结 B'],
    operation_summary_list: [],
  })

  renderPage(session)

  expect(await screen.findByText('刚刚生成的总结步骤')).toBeInTheDocument()
  expect(mocks.explanationStep).toHaveBeenCalledOnce()
  expect(screen.queryByText('正在生成当前步骤…')).not.toBeInTheDocument()
})

test('retries a transient session conflict while loading the next step', async () => {
  const session = explanationSession(false)
  session.steps_info = []
  mocks.explanationStep
    .mockRejectedValueOnce(new ApiError(6011, '该会话存在进行中的任务，请稍后再试'))
    .mockResolvedValueOnce({
      step_number: 1,
      analysis: '重试后加载成功',
      operation_prefix: '继续求解',
      options: ['2', '3'],
      operation_summary_list: [],
    })

  renderPage(session)

  expect(await screen.findByText('重试后加载成功', {}, { timeout: 3_000 })).toBeInTheDocument()
  expect(mocks.explanationStep).toHaveBeenCalledTimes(2)
})

test('shows a retry action instead of an endless loader after step loading fails', async () => {
  const session = explanationSession(false)
  session.steps_info = []
  mocks.explanationStep.mockRejectedValue(new Error('步骤服务暂不可用'))

  renderPage(session)

  expect(await screen.findByText('当前步骤加载失败', {}, { timeout: 3_000 })).toBeInTheDocument()
  expect(screen.getByText('步骤服务暂不可用')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: /重新加载步骤/ })).toBeInTheDocument()
  expect(screen.queryByText('正在生成当前步骤…')).not.toBeInTheDocument()
})

test('advances to the generated summary step after explicit confirmation', async () => {
  const session = explanationSession(false)
  session.total_steps = 2
  const answerResult = {
    session_id: session.session_id,
    question: session.question,
    current_step: 1,
    total_steps: 2,
    in_summary: false,
    knowledge_points: [],
    wrong_answers: [],
    error_analysis: 'True',
    is_correct: true,
    selected_option: 'A',
    correct_option: 'A',
    answer_explanation: '已经掌握当前知识点。',
    awaiting_next: true,
  }
  mocks.advanceExplanation.mockResolvedValue({
    ...session,
    current_step: 2,
    total_steps: 2,
    in_summary: true,
  })
  mocks.explanationStep.mockResolvedValue({
    step_number: 2,
    analysis: '自动进入最终总结',
    operation_prefix: '总结答案',
    options: ['总结 A', '总结 B'],
    operation_summary_list: [],
  })
  renderPage(session, answerResult)

  await submitFirstOption()
  fireEvent.click(screen.getByRole('button', { name: /进入下一步/ }))

  expect(await screen.findByText('自动进入最终总结')).toBeInTheDocument()
  expect(screen.getByText(/总结 · 步骤 2/)).toBeInTheDocument()
})

test('completed steps are reviewable with the recorded option frozen', async () => {
  const session = explanationSession(false)
  session.current_step = 2
  session.total_steps = 2
  session.steps_info = [{
    ...session.steps_info[0],
    analysis: '已完成步骤内容',
    user_selected_option: 'A',
  }]
  session.steps_history = [{
    step_number: 2,
    analysis: '当前总结步骤内容',
    operation_prefix: '总结',
    options: ['总结 A', '总结 B'],
    operation_summary_list: [],
  }]
  renderPage(session)
  await screen.findByText('当前总结步骤内容')

  fireEvent.click(screen.getByRole('button', { name: /步骤 1/ }))

  expect(screen.getByText('已完成步骤内容')).toBeInTheDocument()
  const recordedOption = screen.getByRole('button', { name: /A.*2.*你的选择/ })
  expect(recordedOption).toBeDisabled()
  expect(recordedOption).toHaveClass('is-selected')
  expect(screen.queryByRole('button', { name: /提交检验/ })).not.toBeInTheDocument()

  fireEvent.click(screen.getByRole('button', { name: /步骤 2/ }))
  expect(screen.getByText('当前总结步骤内容')).toBeInTheDocument()
  expect(screen.getByRole('button', { name: /提交检验/ })).toBeInTheDocument()
})

test('an already completed session immediately enters the next task', async () => {
  const session = explanationSession(false)
  session.current_step = 0
  session.total_steps = 2
  session.in_summary = true
  session.steps_info = [{ ...session.steps_info[0], user_selected_option: 'A' }]
  session.steps_history = [{
    step_number: 2,
    analysis: '已完成的最终总结步骤',
    operation_prefix: '最终总结',
    options: ['错误总结', '正确总结'],
    operation_summary_list: [],
    user_selected_option: 'B',
  }]
  renderPage(session)

  expect(await screen.findByText('实验下一任务')).toBeInTheDocument()
  expect(mocks.completeTutor).toHaveBeenCalledWith('session-1')
  expect(screen.queryByText('做得很好，路径已经走通')).not.toBeInTheDocument()
})

test('a completed session opened in review mode stays on the explanation page', async () => {
  const session = explanationSession(false)
  session.current_step = 0
  session.total_steps = 2
  session.in_summary = true
  session.steps_info = [{ ...session.steps_info[0], analysis: '第一步历史内容', user_selected_option: 'A' }]
  session.steps_history = [{
    step_number: 2,
    analysis: '最后一步历史内容',
    operation_prefix: '最终总结',
    options: ['错误总结', '正确总结'],
    operation_summary_list: [],
    user_selected_option: 'B',
  }]

  renderPage(session, undefined, '/ai-tutor/explanation/session-1?review=1')

  expect(await screen.findByText('最后一步历史内容')).toBeInTheDocument()
  expect(screen.queryByText('实验下一任务')).not.toBeInTheDocument()
  expect(mocks.refresh).not.toHaveBeenCalled()
  expect(screen.queryByRole('button', { name: /提交检验|进入下一步/ })).not.toBeInTheDocument()
})

test('review mode restores the saved feedback for each answered step', async () => {
  const session = explanationSession(false)
  session.current_step = 0
  session.total_steps = 2
  session.in_summary = true
  session.steps_info = [{ ...session.steps_info[0], analysis: '第一步历史内容', user_selected_option: 'B' }]
  session.steps_history = [{
    step_number: 2,
    analysis: '第二步历史内容',
    operation_prefix: '最终总结',
    options: ['错误总结', '正确总结'],
    operation_summary_list: [],
    user_selected_option: 'B',
  }]
  session.answer_results = {
    '1': {
      is_correct: false,
      selected_option: 'B',
      correct_option: 'A',
      answer_explanation: '第一步把概念甲与概念乙混淆了。',
      awaiting_next: true,
    },
    '2': {
      is_correct: true,
      selected_option: 'B',
      correct_option: 'B',
      answer_explanation: '第二步正确抓住了综合判断条件。',
      awaiting_next: true,
    },
  }

  renderPage(session, undefined, '/ai-tutor/explanation/session-1?review=1')

  expect(await screen.findByText('第二步正确抓住了综合判断条件。')).toBeInTheDocument()
  expect(screen.getByText('回答正确')).toBeInTheDocument()

  fireEvent.click(screen.getByRole('button', { name: /步骤 1/ }))
  expect(await screen.findByText('第一步把概念甲与概念乙混淆了。')).toBeInTheDocument()
  expect(screen.getByText('需要再巩固一下')).toBeInTheDocument()
  expect(screen.getByText('正确答案：A')).toBeInTheDocument()
})

test('finishing the summary step enters the next task without a standalone completion page', async () => {
  const session = explanationSession(false)
  session.current_step = 2
  session.total_steps = 2
  session.in_summary = true
  session.steps_info = [{ ...session.steps_info[0], user_selected_option: 'A' }]
  session.steps_history = [{
    step_number: 2,
    analysis: '请选择最终总结',
    operation_prefix: '最终总结',
    options: ['错误总结', '正确总结'],
    operation_summary_list: [],
  }]
  mocks.advanceExplanation.mockResolvedValue({
    ...session,
    current_step: 0,
    in_summary: true,
  })
  renderPage(session)
  await screen.findByText('请选择最终总结')

  fireEvent.click(screen.getByText('正确总结').closest('button')!)
  fireEvent.click(screen.getByRole('button', { name: /提交检验/ }))
  await screen.findByText('回答正确')
  fireEvent.click(screen.getByRole('button', { name: /进入下一步/ }))
  expect(await screen.findByText('实验下一任务')).toBeInTheDocument()
  expect(screen.queryByText('做得很好，路径已经走通')).not.toBeInTheDocument()
})

test('a completed free-tutor session returns to the question entry page', async () => {
  mocks.context.experiment_mode = false
  mocks.context.assigned = false
  const session = explanationSession(false)
  session.current_step = 0
  session.in_summary = true

  renderPage(session)

  expect(await screen.findByText('自由提问首页')).toBeInTheDocument()
})
