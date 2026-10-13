import type { AuthSession, PendingTask } from '@/api/types'

const STORAGE_KEYS = {
  auth: 'intellitutor_auth',
  pendingTask: 'intellitutor_pending_task'
} as const

function readValue<T>(key: string): T | null {
  try {
    const value = uni.getStorageSync(key) as T | '' | null
    return value || null
  } catch {
    return null
  }
}

function writeValue<T>(key: string, value: T): void {
  try {
    uni.setStorageSync(key, value)
  } catch (error) {
    console.error(`写入本地缓存失败: ${key}`, error)
  }
}

function removeValue(key: string): void {
  try {
    uni.removeStorageSync(key)
  } catch (error) {
    console.error(`清理本地缓存失败: ${key}`, error)
  }
}

export const readAuthSession = () => readValue<AuthSession>(STORAGE_KEYS.auth)
export const writeAuthSession = (session: AuthSession) => writeValue(STORAGE_KEYS.auth, session)
export const clearAuthSession = () => removeValue(STORAGE_KEYS.auth)

export const readPendingTask = () => readValue<PendingTask>(STORAGE_KEYS.pendingTask)
export const writePendingTask = (task: PendingTask) => writeValue(STORAGE_KEYS.pendingTask, task)
export const clearPendingTask = () => removeValue(STORAGE_KEYS.pendingTask)

