import { describe, expect, it } from 'vitest'
import type { DiscussionFeedbackData, DiscussionSessionData, DiscussionStep } from '@/api/types'
import {
  mergeDiscussionFeedback,
  normalizeDiscussionSession,
  parseCurrentStepNumber
} from '@/pages-session/discussion/state'

function step(overrides: Partial<DiscussionStep>): DiscussionStep {
  return {
    id: 1,
    isActive: false,
    isExpanded: false,
    isMain: true,
    subSteps: [],
    ...overrides
  }
}

function session(overrides: Partial<DiscussionSessionData> = {}): DiscussionSessionData {
  return {
    session_id: 'session-1',
    question: 'question',
    personalized_mode: false,
    current_step: '步骤1：主步骤',
    discussion_tree: [],
    messages_history: [],
    knowledge_points: [],
    is_complete: false,
    ...overrides
  }
}

describe('discussion state normalization', () => {
  it('parses a nested current step number', () => {
    expect(parseCurrentStepNumber('步骤1.1：韦达定理')).toBe('1.1')
  })

  it('activates the current child and only expands its parent', () => {
    const normalized = normalizeDiscussionSession(session({
      current_step: '步骤1.1：韦达定理',
      discussion_tree: [step({
        id: 1,
        isActive: true,
        subSteps: [step({ id: '1-1', isActive: false, isMain: false })]
      })]
    }))

    expect(normalized.discussion_tree[0]).toMatchObject({
      isActive: false,
      isExpanded: true
    })
    expect(normalized.discussion_tree[0].subSteps[0]).toMatchObject({
      isActive: true,
      isExpanded: true
    })
  })

  it('keeps backend active flags when the numbered current step is missing from the tree', () => {
    const normalized = normalizeDiscussionSession(session({
      current_step: '步骤2：尚未返回的步骤',
      discussion_tree: [step({ id: 1, isActive: true })]
    }))

    expect(normalized.discussion_tree[0].isActive).toBe(true)
  })

  it('uses the feedback tree as the next authoritative state', () => {
    const current = session({
      discussion_tree: [step({ id: 1, isActive: true })]
    })
    const feedback: DiscussionFeedbackData = {
      status: 'sub_step',
      current_step: '步骤1.1：韦达定理',
      step: '步骤1：旧字段内容',
      discussion_tree: [step({
        id: 1,
        isActive: true,
        subSteps: [step({ id: '1-1', isMain: false })]
      })],
      messages_history: []
    }

    const next = mergeDiscussionFeedback(current, feedback)

    expect(next.current_step).toBe('步骤1.1：韦达定理')
    expect(next.discussion_tree[0].subSteps).toHaveLength(1)
    expect(next.discussion_tree[0].subSteps[0].isActive).toBe(true)
  })
})
