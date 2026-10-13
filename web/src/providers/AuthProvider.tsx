import { createContext, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { clearAuth, readAuth, saveAuth, subscribeAuth, userIdFromToken } from '@/api/auth-storage'
import type { AuthResult, User } from '@/api/types'

interface AuthContextValue {
  user: User | null
  isAuthenticated: boolean
  login: (result: AuthResult) => void
  updateUser: (user: Partial<User>) => void
  logout: () => void
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function AuthProvider({ children }: { children: ReactNode }) {
  const [stored, setStored] = useState(readAuth)
  useEffect(() => subscribeAuth(() => setStored(readAuth())), [])

  const value = useMemo<AuthContextValue>(() => ({
    user: stored.user,
    isAuthenticated: Boolean(stored.accessToken && stored.refreshToken && userIdFromToken(stored.accessToken)),
    login: result => saveAuth({ accessToken: result.access_token, refreshToken: result.refresh_token, user: result.user }),
    updateUser: user => saveAuth({ ...readAuth(), user: { ...(readAuth().user || { id: userIdFromToken(readAuth().accessToken) || '' }), ...user } }),
    logout: clearAuth,
  }), [stored])

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

export function useAuth() {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth must be used inside AuthProvider')
  return value
}

