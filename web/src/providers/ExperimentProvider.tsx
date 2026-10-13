import { createContext, useContext, useEffect, type ReactNode } from 'react'
import { useQuery } from '@tanstack/react-query'
import { services } from '@/api/services'
import type { ExperimentContext } from '@/api/types'
import { useAuth } from './AuthProvider'

interface ExperimentContextValue {
  context: ExperimentContext | null
  loading: boolean
  refresh: () => Promise<unknown>
}

const Context = createContext<ExperimentContextValue | null>(null)

export function ExperimentProvider({ children }: { children: ReactNode }) {
  const auth = useAuth()
  const query = useQuery({
    queryKey: ['experiment-context', auth.user?.id],
    queryFn: services.experimentContext,
    enabled: auth.isAuthenticated,
    retry: 1,
    refetchInterval: current => current.state.data?.next_route === '/experiment/waiting' ? 5_000 : false,
    refetchOnWindowFocus: true,
    staleTime: 2_000,
  })
  const { refetch } = query
  useEffect(() => {
    const onFocus = () => { if (document.visibilityState === 'visible' && auth.isAuthenticated) void refetch() }
    document.addEventListener('visibilitychange', onFocus)
    return () => document.removeEventListener('visibilitychange', onFocus)
  }, [auth.isAuthenticated, refetch])
  return <Context.Provider value={{ context: query.data ?? null, loading: query.isLoading, refresh: query.refetch }}>{children}</Context.Provider>
}

export function useExperiment() {
  const value = useContext(Context)
  if (!value) throw new Error('useExperiment must be used inside ExperimentProvider')
  return value
}
