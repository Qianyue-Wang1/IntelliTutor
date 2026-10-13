import { ApiError, request } from './request'
import type { ApiResponse, PendingTask, TaskAccepted, TaskStatus, TaskType } from './types'
import { clearPendingTask, readPendingTask, writePendingTask } from '@/utils/storage'

export const TASK_POLL_INTERVAL_MS = 1000
export const TASK_TIMEOUT_MS = 960000
const NETWORK_BACKOFF_MS = [1000, 2000, 4000] as const

export interface PollOptions {
  isCancelled?: () => boolean
}

const delay = (milliseconds: number) => new Promise<void>(resolve => {
  setTimeout(resolve, milliseconds)
})

export function normalizeTaskResult<T>(data: T, javaSessionId: string): T {
  if (javaSessionId && typeof data === 'object' && data !== null && !Array.isArray(data)) {
    return {
      ...(data as Record<string, unknown>),
      session_id: javaSessionId
    } as T
  }
  return data
}

export function createPendingTask(accepted: TaskAccepted, taskType: TaskType): PendingTask {
  return {
    task_id: accepted.task_id,
    session_id: accepted.session_id,
    task_type: taskType,
    started_at: Date.now()
  }
}

export function getTaskStatus<T>(taskId: string): Promise<ApiResponse<TaskStatus<T>>> {
  return request<TaskStatus<T>>({
    path: `/v1/task/${encodeURIComponent(taskId)}`
  })
}

export async function pollPendingTask<T>(
  pending: PendingTask,
  options: PollOptions = {}
): Promise<ApiResponse<T>> {
  let consecutiveNetworkFailures = 0

  while (Date.now() - pending.started_at < TASK_TIMEOUT_MS) {
    if (options.isCancelled?.()) {
      throw new ApiError('任务轮询已暂停', 'TASK_CANCELLED')
    }

    try {
      const response = await getTaskStatus<T>(pending.task_id)
      const task = response.data
      consecutiveNetworkFailures = 0

      if (task.status === 'success') {
        if (task.data == null) {
          clearPendingTask()
          throw new ApiError('推理任务完成但未返回结果', 'EMPTY_TASK_RESULT')
        }
        clearPendingTask()
        return {
          code: 200,
          message: response.message,
          data: normalizeTaskResult(task.data as T, pending.session_id)
        }
      }

      if (task.status === 'failed') {
        clearPendingTask()
        throw new ApiError(task.error_message || 'AI 推理任务失败，请稍后重试', 'TASK_FAILED')
      }

      if (task.status !== 'queued' && task.status !== 'processing') {
        clearPendingTask()
        throw new ApiError(`未知的任务状态：${String(task.status)}`, 'UNKNOWN_TASK_STATUS')
      }

      await delay(TASK_POLL_INTERVAL_MS)
    } catch (error) {
      if (!(error instanceof ApiError) || error.code !== 'NETWORK_ERROR') throw error
      if (consecutiveNetworkFailures >= NETWORK_BACKOFF_MS.length) throw error
      await delay(NETWORK_BACKOFF_MS[consecutiveNetworkFailures])
      consecutiveNetworkFailures += 1
    }
  }

  clearPendingTask()
  throw new ApiError('AI 推理任务处理超时，请稍后重试', 'TASK_TIMEOUT')
}

export async function waitForAcceptedTask<T>(
  acceptedResponse: ApiResponse<TaskAccepted>,
  taskType: TaskType,
  options: PollOptions = {}
): Promise<ApiResponse<T>> {
  const accepted = acceptedResponse.data
  if (!accepted?.task_id || !accepted.session_id) {
    throw new ApiError('后端未返回有效的任务信息', 'INVALID_TASK_ACCEPTED')
  }

  const pending = createPendingTask(accepted, taskType)
  writePendingTask(pending)
  return pollPendingTask<T>(pending, options)
}

export async function resumePendingTask<T>(options: PollOptions = {}): Promise<ApiResponse<T> | null> {
  const pending = readPendingTask()
  if (!pending) return null
  return pollPendingTask<T>(pending, options)
}
