import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import 'katex/dist/katex.min.css'
import './index.css'
import App from './App'
import { AuthProvider } from './providers/AuthProvider'
import { NoticeProvider } from './providers/NoticeProvider'
import { ExperimentProvider } from './providers/ExperimentProvider'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 15_000, retry: 1, refetchOnWindowFocus: false },
    mutations: { retry: 0 },
  },
})

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <BrowserRouter>
      <QueryClientProvider client={queryClient}>
        <AuthProvider><NoticeProvider><ExperimentProvider><App /></ExperimentProvider></NoticeProvider></AuthProvider>
      </QueryClientProvider>
    </BrowserRouter>
  </StrictMode>,
)
