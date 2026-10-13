import { afterEach, describe, expect, it, vi } from 'vitest'
import { request } from '@/api/request'
import { writeAuthSession } from '@/utils/storage'
import { installUniMock } from './helpers/uni'

afterEach(() => {
  vi.clearAllMocks()
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('typed request protocol', () => {
  it.each([200, 202])('accepts business code %s', async code => {
    installUniMock(() => ({ data: { code, message: 'ok', data: { value: 1 } } }))

    await expect(request<{ value: number }>({ path: '/test' })).resolves.toEqual({
      code,
      message: 'ok',
      data: { value: 1 }
    })
  })

  it('turns a business error into a typed ApiError', async () => {
    installUniMock(() => ({ data: { code: 4220, message: '题目格式错误', data: null } }))

    await expect(request({ path: '/test' })).rejects.toMatchObject({
      code: 4220,
      message: '题目格式错误'
    })
  })

  it('uses one refresh request and replays concurrent expired requests once', async () => {
    let refreshCount = 0
    const context = installUniMock(options => {
      if (options.url.endsWith('/v1/auth/refresh')) {
        refreshCount += 1
        return {
          data: {
            code: 200,
            message: 'ok',
            data: { access_token: 'new-token', refresh_token: 'new-refresh' }
          }
        }
      }
      const authorization = (options.header as Record<string, string> | undefined)?.Authorization
      if (authorization === 'Bearer new-token') {
        return { data: { code: 200, message: 'ok', data: { fresh: true } } }
      }
      return { data: { code: 4012, message: 'token expired', data: null } }
    })
    writeAuthSession({
      access_token: 'old-token',
      refresh_token: 'old-refresh',
      user: { id: 'user-1' }
    })

    const [first, second] = await Promise.all([
      request<{ fresh: boolean }>({ path: '/protected/a' }),
      request<{ fresh: boolean }>({ path: '/protected/b' })
    ])

    expect(first.data.fresh).toBe(true)
    expect(second.data.fresh).toBe(true)
    expect(refreshCount).toBe(1)
    expect(context.requestCalls).toHaveLength(5)
  })

  it('clears auth and redirects when refresh fails', async () => {
    vi.useFakeTimers()
    const context = installUniMock(options => options.url.endsWith('/v1/auth/refresh')
      ? { data: { code: 4012, message: 'refresh expired', data: null } }
      : { data: { code: 4012, message: 'access expired', data: null } })
    writeAuthSession({ access_token: 'old', refresh_token: 'expired', user: { id: 'user-1' } })

    const promise = request({ path: '/protected' })
    await expect(promise).rejects.toMatchObject({ code: 4012 })
    await vi.runAllTimersAsync()

    expect(context.storage.has('intellitutor_auth')).toBe(false)
    expect(context.reLaunch).toHaveBeenCalledWith({ url: '/pages/auth/login' })
  })
})
