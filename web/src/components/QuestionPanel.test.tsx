import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { expect, test } from 'vitest'
import QuestionPanel from './QuestionPanel'

const knowledge = [
  { title: '根与系数的关系', description: '对于 $ax^2 + bx + c = 0$，两根之和为 $-b/a$。' },
  { title: '根的判别式', description: '当 $\\Delta \\ge 0$ 时方程有实数根。' },
]

const coreKnowledge = [
  { id: 1, name: '平方的运算规则', type: '运算类' as const, level: '基础层级' as const, content: '$a^2=a\\times a$。' },
  { id: 2, name: '完全平方差公式', type: '公式类' as const, level: '核心方法层级' as const, content: '$(a-b)^2=a^2-2ab+b^2$。' },
]

const problemSolvingHint = {
  basic_idea: '分别计算两个数的平方，再相加求和。',
  skill_enlightenment: '观察 $98$ 接近 $100$，可以写成 $100-2$。想一想该如何代入完全平方差公式？',
  core_mathematical_thought: '凑整简化、化归思想',
  related_method: '完全平方差公式',
}

test('keeps knowledge details collapsed until the full heading row is clicked', async () => {
  const user = userEvent.setup()
  render(<QuestionPanel question="求参数 k 的取值。" knowledge={knowledge} showKnowledgeDetails />)

  const toggle = screen.getByRole('button', { name: /知识点解析.*展开/ })
  const contentId = toggle.getAttribute('aria-controls')
  expect(toggle).toHaveAttribute('aria-expanded', 'false')
  expect(document.getElementById(contentId || '')).toHaveAttribute('aria-hidden', 'true')
  expect(screen.getByText('根与系数的关系', { selector: '.knowledge-chip' })).toBeInTheDocument()

  await user.click(toggle)

  expect(toggle).toHaveAttribute('aria-expanded', 'true')
  expect(toggle).toHaveTextContent('收起')
  expect(document.getElementById(contentId || '')).toHaveAttribute('aria-hidden', 'false')
})

test('does not add the explanation-only knowledge accordion by default', () => {
  render(<QuestionPanel question="讨论题" knowledge={knowledge} />)
  expect(screen.queryByRole('button', { name: /知识点解析/ })).not.toBeInTheDocument()
})

test('derives a teaching plan from core knowledge when the session path is temporarily missing', async () => {
  const user = userEvent.setup()
  render(<QuestionPanel
    question="计算 98²+2² 的值。"
    knowledge={knowledge}
    coreKnowledge={coreKnowledge}
    problemSolvingHint={problemSolvingHint}
    showKnowledgeDetails
    defaultHintExpanded
  />)

  expect(screen.getByText('当前学习内容')).toBeInTheDocument()
  const toggle = screen.getByRole('button', { name: /学习指引.*收起/ })
  const contentId = toggle.getAttribute('aria-controls')
  expect(toggle).toHaveAttribute('aria-expanded', 'true')
  expect(document.getElementById(contentId || '')).toHaveAttribute('aria-hidden', 'false')
  expect(screen.getByText('步骤 1 · 平方的运算规则')).toBeInTheDocument()
  expect(screen.getByText('步骤 2 · 完全平方差公式')).toBeInTheDocument()
  expect(screen.getByText(/为下一步“完全平方差公式”建立基础/)).toBeInTheDocument()
  expect(screen.queryByText('学习起点')).not.toBeInTheDocument()
  expect(screen.queryByText('推荐路径')).not.toBeInTheDocument()
  expect(screen.getByText('完全平方差公式', { selector: '.knowledge-chip' })).toBeInTheDocument()

  await user.click(toggle)

  expect(toggle).toHaveAttribute('aria-expanded', 'false')
  expect(toggle).toHaveTextContent('展开')
  expect(document.getElementById(contentId || '')).toHaveAttribute('aria-hidden', 'true')
})

test('omits the solving hint accordion for legacy sessions without hint data', () => {
  render(<QuestionPanel question="旧会话" knowledge={knowledge} showKnowledgeDetails defaultHintExpanded />)
  expect(screen.queryByRole('button', { name: /学习指引/ })).not.toBeInTheDocument()
})

test('renders the ordered learning path instead of the legacy four-stage guide', () => {
  render(<QuestionPanel
    question="什么是 PCA？"
    knowledge={knowledge}
    problemSolvingHint={problemSolvingHint}
    learningPath={[
      {
        step_number: 1,
        title: '理解降维与方差',
        knowledge_points: ['降维', '方差'],
        connection: '先理解信息如何用方差表示，为选择第一主成分建立依据。',
        assessment_focus: '判断 PCA 为何选择方差较大的方向',
      },
      {
        step_number: 2,
        title: '选择第一主成分',
        knowledge_points: ['投影', '第一主成分'],
        connection: '利用上一步的方差概念，比较不同投影方向保留的信息。',
        assessment_focus: '根据投影方差选择第一主成分',
      },
    ]}
    defaultHintExpanded
  />)

  expect(screen.getByText('步骤 1 · 理解降维与方差')).toBeInTheDocument()
  expect(screen.getByText('步骤 2 · 选择第一主成分')).toBeInTheDocument()
  expect(screen.getAllByText('本步知识点：')[0].closest('p')).toHaveTextContent('本步知识点：降维、方差')
  expect(screen.getByText('利用上一步的方差概念，比较不同投影方向保留的信息。')).toBeInTheDocument()
  expect(screen.queryByText('学习起点')).not.toBeInTheDocument()
  expect(screen.queryByText('推荐路径')).not.toBeInTheDocument()
})
