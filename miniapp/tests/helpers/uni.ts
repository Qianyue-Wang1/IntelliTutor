import { vi } from 'vitest'

export interface MockResponse {
  statusCode?: number
  data?: unknown
  networkError?: string
}

export interface UniMockContext {
  storage: Map<string, unknown>
  requestCalls: UniNamespace.RequestOptions[]
  reLaunch: ReturnType<typeof vi.fn>
  showToast: ReturnType<typeof vi.fn>
}

export function installUniMock(
  responder: (options: UniNamespace.RequestOptions, callIndex: number) => MockResponse
): UniMockContext {
  const storage = new Map<string, unknown>()
  const requestCalls: UniNamespace.RequestOptions[] = []
  const reLaunch = vi.fn()
  const showToast = vi.fn()

  const request = (options: UniNamespace.RequestOptions): UniNamespace.RequestTask => {
    requestCalls.push(options)
    const response = responder(options, requestCalls.length - 1)
    queueMicrotask(() => {
      if (response.networkError) {
        options.fail?.({ errMsg: response.networkError })
        options.complete?.({ errMsg: response.networkError })
        return
      }
      const result = {
        data: response.data,
        statusCode: response.statusCode ?? 200,
        header: {},
        cookies: [],
        errMsg: 'request:ok'
      } as UniNamespace.RequestSuccessCallbackResult
      options.success?.(result)
      options.complete?.({ errMsg: 'request:ok' })
    })
    return {} as UniNamespace.RequestTask
  }

  vi.stubGlobal('uni', {
    request,
    getStorageSync: (key: string) => storage.get(key) || '',
    setStorageSync: (key: string, value: unknown) => storage.set(key, value),
    removeStorageSync: (key: string) => storage.delete(key),
    reLaunch,
    showToast
  } as unknown as typeof uni)

  return { storage, requestCalls, reLaunch, showToast }
}
