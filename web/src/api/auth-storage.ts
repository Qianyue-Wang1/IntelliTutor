import type { User } from './types'

const STORAGE_KEY = 'authStore'
const AUTH_EVENT = 'intellitutor:auth-change'

export interface StoredAuth {
  accessToken: string
  refreshToken: string
  user: User | null
}

function decodeJwt(token: string): Record<string, unknown> | null {
  try {
    const part = token.split('.')[1]
    if (!part) return null
    return JSON.parse(atob(part.replace(/-/g, '+').replace(/_/g, '/'))) as Record<string, unknown>
  } catch {
    return null
  }
}

export function userIdFromToken(token: string): string | null {
  const payload = decodeJwt(token)
  return typeof payload?.sub === 'string' && payload.sub ? payload.sub : null
}

export function tokenIsUsable(token: string): boolean {
  const payload = decodeJwt(token)
  const expires = typeof payload?.exp === 'number' ? payload.exp * 1000 : 0
  return Boolean(userIdFromToken(token) && expires > Date.now())
}

export function readAuth(): StoredAuth {
  try {
    const parsed = JSON.parse(localStorage.getItem(STORAGE_KEY) || '{}') as Record<string, unknown>
    const accessToken = String(parsed.accessToken || parsed.JWTtoken || '')
    const refreshToken = String(parsed.refreshToken || parsed.RefreshToken || '')
    const user = (parsed.user || parsed.userForm || null) as User | null
    return { accessToken, refreshToken, user }
  } catch {
    return { accessToken: '', refreshToken: '', user: null }
  }
}

export function saveAuth(auth: StoredAuth): void {
  localStorage.setItem(STORAGE_KEY, JSON.stringify({
    accessToken: auth.accessToken,
    refreshToken: auth.refreshToken,
    user: auth.user,
    JWTtoken: auth.accessToken,
    RefreshToken: auth.refreshToken,
    authenticated: true,
    userForm: auth.user,
  }))
  window.dispatchEvent(new Event(AUTH_EVENT))
}

export function clearAuth(): void {
  localStorage.removeItem(STORAGE_KEY)
  window.dispatchEvent(new Event(AUTH_EVENT))
}

export function subscribeAuth(listener: () => void): () => void {
  window.addEventListener(AUTH_EVENT, listener)
  window.addEventListener('storage', listener)
  return () => {
    window.removeEventListener(AUTH_EVENT, listener)
    window.removeEventListener('storage', listener)
  }
}

