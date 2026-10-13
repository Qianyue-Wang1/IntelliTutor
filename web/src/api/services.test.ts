import { beforeEach, describe, expect, test, vi } from 'vitest'
import { apiRequest, waitForTask } from './client'
import { services } from './services'

vi.mock('./client', () => ({
  apiDownload: vi.fn(),
  apiRequest: vi.fn(),
  waitForTask: vi.fn(),
}))

describe('Direct LLM service', () => {
  beforeEach(() => vi.clearAllMocks())

  test('reloads the complete session after a follow-up task', async () => {
    vi.mocked(apiRequest)
      .mockResolvedValueOnce({ task_id: 'task-1', session_id: 'session-1' })
      .mockResolvedValueOnce({
        session_id: 'session-1',
        question: 'assigned question',
        messages: [],
        initial_explanation_generated: true,
      })
    vi.mocked(waitForTask).mockResolvedValueOnce({ messages: [] })

    const result = await services.directFollowup('session-1', '没有问题了')

    expect(waitForTask).toHaveBeenCalledWith({ task_id: 'task-1', session_id: 'session-1' })
    expect(apiRequest).toHaveBeenLastCalledWith('/v1/experiment/me/tutor/direct/session-1')
    expect(result.initial_explanation_generated).toBe(true)
  })
})

describe('Machine-learning experiment registration service', () => {
  beforeEach(() => vi.clearAllMocks())

  test('uses the fixed current-experiment endpoint without exposing an experiment code to the student', async () => {
    vi.mocked(apiRequest).mockResolvedValueOnce({ methods: ['SMS_VERIFIED'] })

    const result = await services.experimentRegistrationOptions()

    expect(apiRequest).toHaveBeenCalledWith('/v1/auth/experiment-registration/registration-options', { auth: false })
    expect(result.methods).toEqual(['SMS_VERIFIED'])
  })
})
