import { computed, ref } from 'vue'
import { defineStore } from 'pinia'
import type { AuthResult, UserSummary } from '@/api/types'
import { clearAuthSession, readAuthSession, writeAuthSession } from '@/utils/storage'

export const useAuthStore = defineStore('auth', () => {
  const accessToken = ref('')
  const refreshToken = ref('')
  const user = ref<UserSummary | null>(null)
  const hydrated = ref(false)
  const authenticated = computed(() => Boolean(accessToken.value && user.value?.id))

  function restore(): boolean {
    const session = readAuthSession()
    accessToken.value = session?.access_token || ''
    refreshToken.value = session?.refresh_token || ''
    user.value = session?.user || null
    hydrated.value = true
    return authenticated.value
  }

  function applyAuth(result: AuthResult): void {
    accessToken.value = result.access_token
    refreshToken.value = result.refresh_token
    user.value = result.user
    writeAuthSession({
      access_token: result.access_token,
      refresh_token: result.refresh_token,
      user: result.user
    })
  }

  function updateUser(patch: Partial<UserSummary>): void {
    if (!user.value) return
    user.value = { ...user.value, ...patch }
    writeAuthSession({
      access_token: accessToken.value,
      refresh_token: refreshToken.value,
      user: user.value
    })
  }

  function logout(redirect = true): void {
    accessToken.value = ''
    refreshToken.value = ''
    user.value = null
    clearAuthSession()
    if (redirect) uni.reLaunch({ url: '/pages/auth/login' })
  }

  return {
    accessToken,
    refreshToken,
    user,
    hydrated,
    authenticated,
    restore,
    applyAuth,
    updateUser,
    logout
  }
})

