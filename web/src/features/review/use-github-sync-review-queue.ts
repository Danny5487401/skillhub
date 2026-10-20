import { useQuery } from '@tanstack/react-query'
import { reviewApi } from '@/api/client'

export function useGithubSyncReviewQueue(status: string, namespaceId: number | undefined,
  page: number, sortDirection: 'ASC' | 'DESC', enabled: boolean) {
  return useQuery({
    queryKey: ['reviews', 'sync', status, namespaceId, page, sortDirection],
    queryFn: () => reviewApi.listSync({ status, namespaceId, page, size: 20, sortDirection }),
    enabled: enabled && (namespaceId === undefined || namespaceId > 0),
  })
}
