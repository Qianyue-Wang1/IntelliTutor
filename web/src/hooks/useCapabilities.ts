import { useQuery } from '@tanstack/react-query'
import { services } from '@/api/services'

export function useCapabilities() {
  const query = useQuery({
    queryKey: ['capabilities'],
    queryFn: services.capabilities,
    staleTime: 30_000,
    retry: 1,
    refetchOnWindowFocus: true,
  })
  return {
    ...query,
    capabilities: {
      image_input_enabled: query.data?.image_input_enabled === true,
      experiment_mode_enabled: query.data?.experiment_mode_enabled === true,
    },
  }
}
