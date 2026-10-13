import { useAuthStore } from '@/stores/auth'

export function requireAuthentication(): boolean {
  const authStore = useAuthStore()
  // if (!authStore.hydrated) authStore.restore()
  // if (authStore.authenticated) return true
  // uni.reLaunch({ url: '/pages/auth/login' })
  // return false
  return true
}

