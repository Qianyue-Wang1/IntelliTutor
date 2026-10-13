import { afterEach, expect, test, vi } from 'vitest'
import { apiDownload, apiRequest, waitForTask } from './client'
import { saveAuth } from './auth-storage'
import { services } from './services'

const token = (subject: string, nonce: string) => `x.${btoa(JSON.stringify({ sub: subject, exp: Math.floor(Date.now() / 1000) + 3600, nonce }))}.x`
const json = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status, headers: { 'Content-Type': 'application/json' } })

afterEach(() => vi.unstubAllGlobals())

test('refreshes concurrent unauthorized requests only once', async () => {
  const oldToken = token('user-1', 'old')
  const newToken = token('user-1', 'new')
  saveAuth({ accessToken: oldToken, refreshToken: 'refresh-1', user: { id: 'user-1' } })
  let refreshCalls = 0
  vi.stubGlobal('fetch', vi.fn(async (input: string | URL | Request, init?: RequestInit) => {
    const url = String(input)
    if (url.endsWith('/v1/auth/refresh')) {
      refreshCalls += 1
      await Promise.resolve()
      return json({ code: 200, message: 'ok', data: { access_token: newToken } })
    }
    const authorization = new Headers(init?.headers).get('Authorization')
    if (authorization === `Bearer ${oldToken}`) return json({ code: 4012, message: 'expired', data: null }, 401)
    return json({ code: 200, message: 'ok', data: { value: 1 } })
  }))

  const [first, second] = await Promise.all([
    apiRequest<{ value: number }>('/v1/test'),
    apiRequest<{ value: number }>('/v1/test'),
  ])
  expect(first.value + second.value).toBe(2)
  expect(refreshCalls).toBe(1)
})

test('supports abort signals', async () => {
  const controller = new AbortController()
  controller.abort()
  vi.stubGlobal('fetch', vi.fn((_input, init?: RequestInit) => {
    if (init?.signal?.aborted) return Promise.reject(new DOMException('aborted', 'AbortError'))
    return Promise.resolve(json({ code: 200, message: 'ok', data: null }))
  }))
  await expect(apiRequest('/v1/test', { auth: false, signal: controller.signal })).rejects.toMatchObject({ name: 'AbortError' })
})

test('accepts the Spring asynchronous task envelope', async () => {
  vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(json({ code: 202, message: 'accepted', data: { task_id: 'task-1', session_id: 'session-1' } }))))
  await expect(apiRequest<{ task_id: string }>('/v1/explanation/init', { method: 'POST', auth: false, body: {} }))
    .resolves.toMatchObject({ task_id: 'task-1' })
})

test('uses the Java session id when an asynchronous task succeeds', async () => {
  vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(json({
    code: 200, message: 'ok', data: { task_id: 'task-1', status: 'success', data: { session_id: 'python-id', question: 'q' } },
  }))))
  await expect(waitForTask<{ session_id: string; question: string }>({ task_id: 'task-1', session_id: 'java-id' }))
    .resolves.toEqual({ session_id: 'java-id', question: 'q' })
})

test('keeps the requested Java id when loading explanation and discussion state', async () => {
  vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(json({
    code: 200, message: 'ok', data: { session_id: 'python-id', question: 'q' },
  }))))

  await expect(services.explanation('java-explanation-id'))
    .resolves.toMatchObject({ session_id: 'java-explanation-id', question: 'q' })
  await expect(services.discussion('java-discussion-id'))
    .resolves.toMatchObject({ session_id: 'java-discussion-id', question: 'q' })
})

test('surfaces asynchronous task failures', async () => {
  vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(json({
    code: 200, message: 'ok', data: { task_id: 'task-1', status: 'failed', error_message: '模型不可用' },
  }))))
  await expect(waitForTask({ task_id: 'task-1' })).rejects.toThrow('模型不可用')
})

test('does not download a JSON error body as an Excel file', async () => {
  vi.stubGlobal('fetch', vi.fn(() => Promise.resolve(json({ code: 5000, message: '服务器内部错误', success: false }))))

  await expect(apiDownload('/v1/experiment/me/records/assignment-1/export')).rejects.toThrow('服务器内部错误')
})
