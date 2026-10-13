import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { fireEvent, render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { beforeEach, expect, test, vi } from 'vitest'
import type { DiscussionSession } from '@/api/types'
import { isTutorCompletionHeld } from './completionHold'

const mocks = vi.hoisted(() => ({
  discussion: vi.fn(),
  discussionFeedback: vi.fn(),
  updateDiscussion: vi.fn(),
  completeTutor: vi.fn(),
  refresh: vi.fn(),
  notify: vi.fn(),
  context: { experiment_mode: false, assigned: false, next_route: '/ai-tutor/discussion/session-1' },
}))

vi.mock('@/api/services', () => ({ services: {
  discussion: mocks.discussion,
  discussionFeedback: mocks.discussionFeedback,
  updateDiscussion: mocks.updateDiscussion,
  completeTutor: mocks.completeTutor,
} }))
vi.mock('@/providers/AuthProvider', () => ({ useAuth: () => ({ user: { id: 'student-1' } }) }))
vi.mock('@/providers/ExperimentProvider', () => ({ useExperiment: () => ({ context: mocks.context, refresh: mocks.refresh }) }))
vi.mock('@/providers/NoticeProvider', () => ({ useNotice: () => mocks.notify }))
vi.mock('@/features/experiment/telemetry', () => ({ useExperimentTelemetry: vi.fn() }))

import DiscussionPage, { discussionMessagesForDisplay, normalizeDiscussionSummary } from './DiscussionPage'

const summaryMarkdown = `## 问题总结

- **问题回顾**：计算 $1+1$
- **解题步骤**：直接相加
- **答案总结**：结果为 $2$`

function discussionSession(complete = false): DiscussionSession {
  return {
    session_id: 'session-1',
    question: '1 + 1 = ?',
    personalized_mode: false,
    adaptive_mode: false,
    cross_session_enabled: false,
    persist_learner_state: false,
    current_step: '步骤1',
    discussion_tree: [{
      id: 1,
      title: '步骤 1',
      description: '分析加法',
      isActive: !complete,
      isExpanded: true,
      isMain: true,
      subSteps: [],
    }],
    messages_history: complete
      ? [{ content: '🎉 恭喜！问题研讨已完成。', type: 'system', time: '2026-09-09T14:31:37' }]
      : [{ content: '请分析这道题。', type: 'system', time: '2026-09-09T14:30:00' }],
    knowledge_points: [],
    is_complete: complete,
    summary_text: complete ? summaryMarkdown : '',
  }
}

function renderPage(session: DiscussionSession) {
  mocks.discussion.mockResolvedValue(session)
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/ai-tutor/discussion/session-1']}>
        <Routes>
          <Route path="/ai-tutor/discussion/:sessionId" element={<DiscussionPage />} />
          <Route path="/experiment/waiting" element={<div>实验下一任务</div>} />
          <Route path="/ai-tutor" element={<div>自由提问首页</div>} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  )
}

beforeEach(() => {
  vi.clearAllMocks()
  sessionStorage.clear()
  mocks.context.experiment_mode = false
  mocks.context.assigned = false
  mocks.context.next_route = '/ai-tutor/discussion/session-1'
  mocks.refresh.mockResolvedValue({ data: { next_route: '/experiment/waiting' } })
  mocks.completeTutor.mockResolvedValue(undefined)
  mocks.updateDiscussion.mockResolvedValue(undefined)
})

test('normalizes a legacy JSON summary into displayable Markdown', () => {
  expect(normalizeDiscussionSummary(JSON.stringify({
    problem_review: '回顾题目',
    solution_steps: ['先计算', '再验证'],
    answer_summary: '答案为 2',
  }))).toBe('## 问题总结\n\n- **问题回顾**：回顾题目\n\n- **解题步骤**：\n  1. 先计算\n  2. 再验证\n\n- **答案总结**：答案为 2')
})

test('replaces the terminal acknowledgement with exactly one summary message', () => {
  const messages = discussionMessagesForDisplay(discussionSession(true))

  expect(messages).toHaveLength(1)
  expect(messages[0].content).toBe(summaryMarkdown)
  expect(messages[0].content).not.toContain('步骤4')
})

test('shows the discussion teaching plan in the learning guide', async () => {
  const session = discussionSession(false)
  Object.assign(session, {
    learning_path: [{
      step_number: 1,
      title: '理解降维目标',
      knowledge_points: ['降维'],
      connection: '先理解为什么压缩信息，再学习如何选择主成分。',
      assessment_focus: '能说明降维要解决的问题',
    }, {
      step_number: 2,
      title: '选择主成分',
      knowledge_points: ['方差', '主成分'],
      connection: '承接降维目标，用方差判断哪些方向保留更多信息。',
      assessment_focus: '能说明为何选择方差较大的方向',
    }],
  })

  renderPage(session)

  expect(await screen.findByText('步骤 1 · 理解降维目标')).toBeInTheDocument()
  expect(screen.getByText('步骤 2 · 选择主成分')).toBeInTheDocument()
  expect(screen.queryByText('学习起点')).not.toBeInTheDocument()
})

test('sends an objective choice immediately and shows it as the learner reply', async () => {
  const session = discussionSession(false)
  session.current_options = ['2', '3', '4', '不知道']
  mocks.discussionFeedback.mockResolvedValue({
    status: 'sub_step',
    step: '步骤1.1：答对了。下一题是什么？',
    current_options: ['选项一', '选项二', '不知道'],
    discussion_tree: session.discussion_tree,
    messages_history: [
      ...session.messages_history,
      { content: '2', type: 'user', time: '2026-09-09T14:30:10' },
      { content: '步骤1.1：答对了。下一题是什么？', type: 'system', time: '2026-09-09T14:30:11' },
    ],
  })

  renderPage(session)
  fireEvent.click(await screen.findByRole('button', { name: /A\s*2/ }))

  await waitFor(() => expect(mocks.discussionFeedback).toHaveBeenCalledWith(
      'session-1', '2', 'option', undefined, '1',
  ))
  expect(await screen.findByText('2', { selector: '.message--user p' })).toBeInTheDocument()
  expect(screen.getByRole('button', { name: /A\s*选项一/ })).toBeInTheDocument()
  expect(screen.queryByRole('button', { name: /不知道/ })).not.toBeInTheDocument()
})

test('keeps a failed objective choice out of the free-text composer', async () => {
  const session = discussionSession(false)
  session.current_options = ['选项 A', '选项 B']
  mocks.discussionFeedback.mockRejectedValueOnce(new Error('评估暂时失败'))

  renderPage(session)
  fireEvent.click(await screen.findByRole('button', { name: /A\s*选项 A/ }))

  await waitFor(() => expect(mocks.discussionFeedback).toHaveBeenCalledWith(
      'session-1', '选项 A', 'option', undefined, '1',
  ))
  await waitFor(() => expect(screen.getByPlaceholderText(/提问、补充/)).toHaveValue(''))
  expect(screen.getByRole('button', { name: /发送/ })).toBeDisabled()
  expect(screen.getByRole('button', { name: /A\s*选项 A/ })).toBeEnabled()
})

test('retries preparing the next step without adding another learner answer', async () => {
  const session = discussionSession(false)
  session.current_options = ['重试进入下一步']
  mocks.discussionFeedback.mockResolvedValue({
    status: 'main_step',
    step: '步骤2：继续下一学习目标。',
    current_options: ['选项一', '选项二'],
    discussion_tree: session.discussion_tree,
    messages_history: [
      ...session.messages_history,
      { content: '回答正确。下一学习内容暂未准备好，请重试。', type: 'system', time: '2026-09-09T14:30:11' },
      { content: '步骤2：继续下一学习目标。', type: 'system', time: '2026-09-09T14:30:12' },
    ],
  })

  renderPage(session)
  expect(await screen.findByRole('button', { name: '重试进入下一步' })).toHaveAttribute('data-telemetry-id', 'discussion-next-step-retry')
  fireEvent.click(await screen.findByRole('button', { name: '重试进入下一步' }))

  await waitFor(() => expect(mocks.discussionFeedback).toHaveBeenCalledWith(
      'session-1', '重试进入下一步', 'option', undefined, '1',
  ))
  expect(screen.queryByText('重试进入下一步', { selector: '.message--user p' })).not.toBeInTheDocument()
  expect(await screen.findByText('步骤2：继续下一学习目标。')).toBeInTheDocument()
})

test('marks composer submissions as free text questions', async () => {
  const session = discussionSession(false)
  mocks.discussionFeedback.mockResolvedValue({
    status: 'sub_step',
    step: '步骤1：以上解释理解了吗？',
    current_options: ['理解了', '还不理解'],
    discussion_tree: session.discussion_tree,
    messages_history: session.messages_history,
  })

  renderPage(session)
  fireEvent.change(await screen.findByPlaceholderText(/提问、补充/), {
    target: { value: '解释变量是模型中的输入特征' },
  })
  fireEvent.click(screen.getByRole('button', { name: /发送/ }))

  await waitFor(() => expect(mocks.discussionFeedback).toHaveBeenCalledWith(
    'session-1',
    '解释变量是模型中的输入特征',
    'free_text',
      undefined,
      '1',
  ))
})

test('shows a completed summary in the conversation and replaces the composer with completion action', async () => {
  renderPage(discussionSession(true))

  expect(await screen.findByRole('heading', { name: '问题总结' })).toBeInTheDocument()
  expect(screen.getAllByRole('heading', { name: '问题总结' })).toHaveLength(1)
  expect(screen.queryByPlaceholderText(/说说你的想法/)).not.toBeInTheDocument()
  expect(screen.getByRole('button', { name: /完成辅导/ })).toBeInTheDocument()
  expect(screen.queryByText('步骤 2')).not.toBeInTheDocument()

  fireEvent.click(screen.getByRole('button', { name: /完成辅导/ }))
  expect(await screen.findByText('自由提问首页')).toBeInTheDocument()
})

test('routes an experiment discussion directly to the round quiz when teaching completes', async () => {
  mocks.context.experiment_mode = true
  mocks.context.assigned = true
  const session = discussionSession(false)
  mocks.discussionFeedback.mockResolvedValue({
    status: 'completed',
    summary: summaryMarkdown,
    discussion_tree: session.discussion_tree,
    messages_history: [
      ...session.messages_history,
      { content: '已经解决', type: 'user', time: '2026-09-09T14:31:30' },
      { content: '🎉 恭喜！问题研讨已完成。', type: 'system', time: '2026-09-09T14:31:37' },
    ],
  })
  renderPage(session)

  fireEvent.change(await screen.findByPlaceholderText(/提问、补充/), { target: { value: '已经解决' } })
  fireEvent.click(screen.getByRole('button', { name: /发送/ }))

  expect(await screen.findByText('实验下一任务')).toBeInTheDocument()
  expect(mocks.completeTutor).toHaveBeenCalledWith('session-1')
  await waitFor(() => expect(mocks.refresh).toHaveBeenCalledOnce())
  expect(isTutorCompletionHeld('/ai-tutor/discussion/session-1')).toBe(false)
  expect(screen.queryByRole('button', { name: /完成辅导/ })).not.toBeInTheDocument()
})
