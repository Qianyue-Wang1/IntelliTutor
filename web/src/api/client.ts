import { clearAuth, readAuth, saveAuth } from './auth-storage'
import type { ApiEnvelope } from './types'

const API_BASE = (import.meta.env.VITE_API_BASE_URL || '/api').replace(/\/$/, '')
const AUTH_CODES = new Set([4010, 4011, 4012])

export class ApiError extends Error {
  constructor(public code: number, message: string, public status?: number) {
    super(message)
    this.name = 'ApiError'
  }
}

let refreshPromise: Promise<string> | null = null

async function refreshAccessToken(): Promise<string> {
  if (refreshPromise) return refreshPromise
  refreshPromise = (async () => {
    const auth = readAuth()
    if (!auth.refreshToken) throw new ApiError(4010, '登录状态已失效')
    const response = await fetch(`${API_BASE}/v1/auth/refresh`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ refresh_token: auth.refreshToken }),
    })
    const payload = await response.json() as ApiEnvelope<{ access_token: string; refresh_token?: string }>
    if (!response.ok || payload.code !== 200 || !payload.data?.access_token) {
      throw new ApiError(payload.code || response.status, payload.message || '登录状态已失效', response.status)
    }
    saveAuth({
      ...auth,
      accessToken: payload.data.access_token,
      refreshToken: payload.data.refresh_token || auth.refreshToken,
    })
    return payload.data.access_token
  })().catch((error) => {
    clearAuth()
    throw error
  }).finally(() => { refreshPromise = null })
  return refreshPromise
}

interface RequestOptions extends Omit<RequestInit, 'body'> {
  body?: unknown
  auth?: boolean
  retryAuth?: boolean
}

export async function apiRequest<T>(path: string, options: RequestOptions = {}): Promise<T> {
  const { body, auth = true, retryAuth = true, headers, ...init } = options
  const token = readAuth().accessToken
  const requestHeaders = new Headers(headers)
  if (body !== undefined) requestHeaders.set('Content-Type', 'application/json')
  if (auth && token) requestHeaders.set('Authorization', `Bearer ${token}`)

  let response: Response
  try {
    response = await fetch(`${API_BASE}${path}`, {
      ...init,
      headers: requestHeaders,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') throw error
    throw new ApiError(0, '网络连接失败，请检查服务是否可用')
  }

  let payload: ApiEnvelope<T>
  try {
    payload = await response.json() as ApiEnvelope<T>
  } catch {
    throw new ApiError(response.status, '服务返回了无法解析的响应', response.status)
  }

  if (auth && retryAuth && (response.status === 401 || AUTH_CODES.has(payload.code))) {
    await refreshAccessToken()
    return apiRequest<T>(path, { ...options, retryAuth: false })
  }
  if (!response.ok || (payload.code !== 200 && payload.code !== 202)) {
    const details = payload.errors?.map(item => item.message).join('；')
    throw new ApiError(payload.code || response.status, details || payload.message || '请求失败', response.status)
  }
  return payload.data
}

export async function apiDownload(path: string, retryAuth = true): Promise<Blob> {
  const token = readAuth().accessToken
  const response = await fetch(`${API_BASE}${path}`, {
    headers: token ? { Authorization: `Bearer ${token}` } : undefined,
  })
  if (retryAuth && response.status === 401) {
    await refreshAccessToken()
    return apiDownload(path, false)
  }
  if (!response.ok || response.headers.get('Content-Type')?.includes('application/json')) {
    let message = '文件导出失败'
    try {
      const payload = (await response.json()) as ApiEnvelope<unknown>
      message = payload.message || message
    } catch { /* non-JSON error */ }
    throw new ApiError(response.status, message, response.status)
  }
  return response.blob()
}

export async function waitForTask<T>(accepted: TaskAccepted, signal?: AbortSignal): Promise<T> {
  const startedAt = Date.now()
  while (Date.now() - startedAt < 16 * 60 * 1000) {
    if (signal?.aborted) throw new DOMException('已取消', 'AbortError')
    const task = await apiRequest<TaskStatus<T>>(`/v1/task/${encodeURIComponent(accepted.task_id)}`, { signal })
    if (task.status === 'success') {
      if (task.data == null) throw new ApiError(6020, 'AI 任务完成但未返回结果')
      if (accepted.session_id && typeof task.data === 'object' && !Array.isArray(task.data)) {
        return { ...(task.data as object), session_id: accepted.session_id } as T
      }
      return task.data
    }
    if (task.status === 'failed') throw new ApiError(6020, task.error_message || 'AI 推理失败，请稍后重试')
    await new Promise<void>((resolve, reject) => {
      const onAbort = () => { window.clearTimeout(timer); reject(new DOMException('已取消', 'AbortError')) }
      const timer = window.setTimeout(() => { signal?.removeEventListener('abort', onAbort); resolve() }, 1000)
      signal?.addEventListener('abort', onAbort, { once: true })
    })
  }
  throw new ApiError(6020, 'AI 推理超时，请稍后再试')
}

import type { TaskAccepted, TaskStatus } from './types'
