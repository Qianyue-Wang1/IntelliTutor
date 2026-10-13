import { API_BASE_URL } from '@/config/env'
import { clearAuthSession, readAuthSession, writeAuthSession } from '@/utils/storage'
import type { ApiResponse, RefreshResult } from './types'
import { ErrorCodes } from './types'

export type HttpMethod = 'GET' | 'POST' | 'PUT' | 'DELETE'

export interface RequestOptions<TBody = unknown> {
  path: string
  method?: HttpMethod
  data?: TBody
  auth?: boolean
  timeout?: number
  retried?: boolean
}

export class ApiError extends Error {
  readonly code: number | string
  readonly statusCode?: number

  constructor(message: string, code: number | string, statusCode?: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.statusCode = statusCode
  }
}

interface RawResponse<T> {
  statusCode: number
  data: T
}

function rawRequest<T, TBody = unknown>(options: RequestOptions<TBody>): Promise<RawResponse<T>> {
  const session = readAuthSession()
  const header: Record<string, string> = {
    'content-type': 'application/json'
  }

  if (options.auth !== false && session?.access_token) {
    header.Authorization = `Bearer ${session.access_token}`
  }

  return new Promise((resolve, reject) => {
    uni.request({
      url: `${API_BASE_URL}${options.path}`,
      method: options.method || 'GET',
      data: options.data as string | AnyObject | ArrayBuffer | undefined,
      header,
      timeout: options.timeout || 120000,
      success: response => {
        resolve({
          statusCode: response.statusCode,
          data: response.data as T
        })
      },
      fail: error => {
        reject(new ApiError(error.errMsg || '网络连接异常', 'NETWORK_ERROR'))
      }
    })
  })
}

let refreshPromise: Promise<boolean> | null = null

function redirectToLogin(): void {
  clearAuthSession()
  setTimeout(() => {
    uni.reLaunch({ url: '/pages/auth/login' })
  }, 0)
}

async function refreshAccessToken(): Promise<boolean> {
  if (refreshPromise) return refreshPromise

  refreshPromise = (async () => {
    const session = readAuthSession()
    if (!session?.refresh_token) return false

    try {
      const response = await rawRequest<ApiResponse<RefreshResult>, { refresh_token: string }>({
        path: '/v1/auth/refresh',
        method: 'POST',
        data: { refresh_token: session.refresh_token },
        auth: false
      })

      if (response.statusCode < 200 || response.statusCode >= 300 || response.data.code !== 200) {
        return false
      }

      writeAuthSession({
        ...session,
        access_token: response.data.data.access_token,
        refresh_token: response.data.data.refresh_token || session.refresh_token
      })
      return true
    } catch {
      return false
    }
  })().finally(() => {
    refreshPromise = null
  })

  return refreshPromise
}

function isUnauthorized(code: number): boolean {
  return code === ErrorCodes.UNAUTHORIZED
    || code === ErrorCodes.TOKEN_INVALID
    || code === ErrorCodes.TOKEN_EXPIRED
}

export async function request<T, TBody = unknown>(
  options: RequestOptions<TBody>
): Promise<ApiResponse<T>> {
  const response = await rawRequest<ApiResponse<T>, TBody>(options)
  const body = response.data

  if (response.statusCode === 401 || (body && isUnauthorized(body.code))) {
    if (options.auth !== false && !options.retried && await refreshAccessToken()) {
      return request<T, TBody>({ ...options, retried: true })
    }
    redirectToLogin()
    throw new ApiError(body?.message || '登录状态已失效', body?.code || response.statusCode, response.statusCode)
  }

  if (response.statusCode < 200 || response.statusCode >= 300) {
    throw new ApiError(body?.message || `请求失败 (${response.statusCode})`, body?.code || 'HTTP_ERROR', response.statusCode)
  }

  if (!body || (body.code !== ErrorCodes.SUCCESS && body.code !== ErrorCodes.ACCEPTED)) {
    throw new ApiError(body?.message || '请求失败', body?.code || 'INVALID_RESPONSE', response.statusCode)
  }

  return body
}

export function showApiError(error: unknown, fallback = '操作失败，请稍后重试'): void {
  const message = error instanceof Error && error.message ? error.message : fallback
  uni.showToast({ title: message, icon: 'none', duration: 3000 })
}
