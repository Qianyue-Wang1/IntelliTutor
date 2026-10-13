import { afterEach, describe, expect, it, vi } from 'vitest'
import {
  createPendingTask,
  normalizeTaskResult,
  pollPendingTask,
  resumePendingTask,
  TASK_TIMEOUT_MS,
  waitForAcceptedTask
} from '@/api/task'
import type { PendingTask, TaskStatus } from '@/api/types'
import { readPendingTask, writePendingTask } from '@/utils/storage'
import { installUniMock, type MockResponse } from './helpers/uni'

afterEach(() => {
  vi.clearAllMocks()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

function taskResponse(status: TaskStatus['status'], data?: unknown, errorMessage?: string): MockResponse {
  return {
    data: {
      code: 200,
      message: 'ok',
      data: {
        task_id: 'task-1',
        status,
        data,
        error_message: errorMessage || null
      }
    }
  }
}

function pending(startedAt = Date.now()): PendingTask {
  return {
    task_id: 'task-1',
    session_id: 'java-session',
    task_type: 'EXPLANATION_INIT',
    started_at: startedAt
  }
}

describe('async task protocol', () => {
  it('forces the Java session id onto the successful Python result', () => {
    expect(normalizeTaskResult({ session_id: 'python-session', value: 1 }, 'java-session')).toEqual({
      session_id: 'java-session',
      value: 1
    })
  })

  it('persists an accepted task and clears it after success', async () => {
    installUniMock(() => taskResponse('success', { session_id: 'python-session', value: 1 }))

    const result = await waitForAcceptedTask<{ session_id: string; value: number }>({
      code: 202,
      message: 'accepted',
      data: { task_id: 'task-1', session_id: 'java-session' }
    }, 'EXPLANATION_INIT')

    expect(result.data.session_id).toBe('java-session')
    expect(readPendingTask()).toBeNull()
  })

  it('restores a persisted task after relaunch', async () => {
    installUniMock(() => taskResponse('success', { restored: true }))
    writePendingTask(pending())

    const result = await resumePendingTask<{ restored: boolean }>()

    expect(result?.data.restored).toBe(true)
    expect(readPendingTask()).toBeNull()
  })

  it('handles queued and processing states before success', async () => {
    vi.useFakeTimers()
    const states: MockResponse[] = [
      taskResponse('queued'),
      taskResponse('processing'),
      taskResponse('success', { done: true })
    ]
    installUniMock((_options, index) => states[index] || states[states.length - 1])

    const promise = pollPendingTask<{ done: boolean }>(pending())
    await vi.runAllTimersAsync()

    await expect(promise).resolves.toMatchObject({ data: { done: true } })
  })

  it('retries three network failures with 1/2/4 second backoff', async () => {
    vi.useFakeTimers()
    installUniMock((_options, index) => index < 3
      ? { networkError: 'request:fail offline' }
      : taskResponse('success', { recovered: true }))

    const promise = pollPendingTask<{ recovered: boolean }>(pending())
    await vi.runAllTimersAsync()

    await expect(promise).resolves.toMatchObject({ data: { recovered: true } })
  })

  it('surfaces a failed task and removes its recovery record', async () => {
    const context = installUniMock(() => taskResponse('failed', null, '模型服务失败'))
    writePendingTask(pending())

    await expect(pollPendingTask(pending())).rejects.toMatchObject({
      code: 'TASK_FAILED',
      message: '模型服务失败'
    })
    expect(context.storage.has('intellitutor_pending_task')).toBe(false)
  })

  it('rejects tasks whose persisted start time is already timed out', async () => {
    const context = installUniMock(() => taskResponse('processing'))
    const expired = pending(Date.now() - TASK_TIMEOUT_MS - 1)
    writePendingTask(expired)

    await expect(pollPendingTask(expired)).rejects.toMatchObject({ code: 'TASK_TIMEOUT' })
    expect(context.requestCalls).toHaveLength(0)
    expect(context.storage.has('intellitutor_pending_task')).toBe(false)
  })

  it('creates stable recovery metadata', () => {
    vi.setSystemTime(new Date('2026-08-14T00:00:00Z'))
    expect(createPendingTask({ task_id: 't', session_id: 's' }, 'DISCUSSION_FEEDBACK')).toEqual({
      task_id: 't',
      session_id: 's',
      task_type: 'DISCUSSION_FEEDBACK',
      started_at: Date.now()
    })
  })
})
