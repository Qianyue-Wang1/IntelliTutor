import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { renderHook, waitFor } from '@testing-library/react'
import type { ReactNode } from 'react'
import { expect, test, vi } from 'vitest'
import { services } from '@/api/services'
import { useCapabilities } from './useCapabilities'

vi.mock('@/api/services', () => ({ services: { capabilities: vi.fn() } }))

function wrapper({ children }: { children: ReactNode }) {
  return <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>{children}</QueryClientProvider>
}

test('fails closed when capability request fails', async () => {
  vi.mocked(services.capabilities).mockRejectedValue(new Error('offline'))
  const { result } = renderHook(() => useCapabilities(), { wrapper })
  await waitFor(() => expect(result.current.isError).toBe(true), { timeout: 3000 })
  expect(result.current.capabilities.image_input_enabled).toBe(false)
})

test('only enables images for an explicit true value', async () => {
  vi.mocked(services.capabilities).mockResolvedValueOnce({ image_input_enabled: true })
  const { result } = renderHook(() => useCapabilities(), { wrapper })
  await waitFor(() => expect(result.current.isSuccess).toBe(true))
  expect(result.current.capabilities.image_input_enabled).toBe(true)
})
