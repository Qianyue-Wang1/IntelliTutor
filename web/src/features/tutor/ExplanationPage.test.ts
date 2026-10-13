import { expect, test } from 'vitest'
import type { ExplanationSession } from '@/api/types'
import { getStepTitle } from './ExplanationPage'

function session(overrides: Partial<ExplanationSession>): ExplanationSession {
  return {
    session_id: 'session-1',
    question: '测试题目',
    personalized_mode: false,
    adaptive_mode: false,
    cross_session_enabled: false,
    persist_learner_state: false,
    current_step: 2,
    total_steps: 3,
    in_summary: false,
    knowledge_points: [],
    wrong_answers: [],
    steps_info: [],
    steps_dict: {},
    steps_op_prefix_dic: {},
    ...overrides,
  }
}

test('derives concise step titles from the existing explanation data', () => {
  expect(getStepTitle(session({ steps_op_prefix_dic: { 步骤2: '由根与系数关系建立关于 k 的方程' } }), 2))
    .toBe('由根与系数关系建立关于 k 的方程')

  expect(getStepTitle(session({ steps_data: { 步骤2: { 具体操作: '求解关于 k 的方程' } } }), 2))
    .toBe('求解关于 k 的方程')
})

test('falls back to the operation section without exposing markdown syntax', () => {
  const title = getStepTitle(session({ steps_dict: { 步骤3: '## 具体操作\n**检验实数根条件**' } }), 3)
  expect(title).toBe('检验实数根条件')
})

test('prefers the shared learning path title over legacy step titles', () => {
  const title = getStepTitle(session({
    learning_path: [{
      step_number: 2,
      title: '由协方差过渡到特征分解',
      knowledge_points: ['协方差矩阵', '特征值'],
      connection: '使用前一步的方差概念建立矩阵表示。',
      assessment_focus: '解释最大特征值与第一主成分的关系',
    }],
    steps_op_prefix_dic: { 步骤2: '旧标题' },
  }), 2)

  expect(title).toBe('由协方差过渡到特征分解')
})
