import { useSyncExternalStore } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { githubDeepDiscoveryApi, type GithubDeepDiscoveryConfigurationInput, type GithubDeepDiscoveryRepositoryQuery } from '@/api/client'

export const DEEP_DISCOVERY_QUERY_KEY = ['github-deep-discovery'] as const

function subscribeVisibility(listener: () => void) {
  document.addEventListener('visibilitychange', listener)
  return () => document.removeEventListener('visibilitychange', listener)
}

function visibleSnapshot() { return document.visibilityState === 'visible' }

export function useDeepDiscoveryVisible() {
  return useSyncExternalStore(subscribeVisibility, visibleSnapshot, () => true)
}

export function useGithubDeepDiscoveryConfiguration(visible: boolean) {
  return useQuery({
    queryKey: [...DEEP_DISCOVERY_QUERY_KEY, 'configuration'],
    queryFn: ({ signal }) => githubDeepDiscoveryApi.configuration(signal),
    enabled: visible,
    staleTime: 30_000,
  })
}

export function useUpdateGithubDeepDiscoveryConfiguration() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: GithubDeepDiscoveryConfigurationInput) => githubDeepDiscoveryApi.updateConfiguration(body),
    onSuccess: () => {
      void queryClient.invalidateQueries({
        queryKey: DEEP_DISCOVERY_QUERY_KEY,
        predicate: (query) => query.queryKey[1] !== 'repositories',
      })
    },
  })
}

export function useGithubDeepDiscoverySummary(from: string | undefined, until: string | undefined, visible: boolean) {
  return useQuery({
    queryKey: [...DEEP_DISCOVERY_QUERY_KEY, 'summary', from, until],
    queryFn: ({ signal }) => githubDeepDiscoveryApi.summary(from!, until!, signal),
    enabled: visible && Boolean(from && until),
  })
}

export function useGithubDeepDiscoveryProgress(visible: boolean) {
  return useQuery({
    queryKey: [...DEEP_DISCOVERY_QUERY_KEY, 'progress'],
    queryFn: ({ signal }) => githubDeepDiscoveryApi.progress(signal),
    enabled: visible,
    refetchInterval: visible ? 30_000 : false,
    refetchIntervalInBackground: false,
  })
}

export function useGithubDeepDiscoveryRepositories(query: GithubDeepDiscoveryRepositoryQuery, enabled: boolean,
  refreshVersion = 0, refreshIntervalMs?: number) {
  return useQuery({
    queryKey: [...DEEP_DISCOVERY_QUERY_KEY, 'repositories', query, refreshVersion],
    queryFn: ({ signal }) => githubDeepDiscoveryApi.repositories(query, signal),
    enabled,
    staleTime: refreshIntervalMs ? 0 : Infinity,
    refetchOnReconnect: false,
    refetchOnWindowFocus: false,
    refetchInterval: enabled && refreshIntervalMs ? refreshIntervalMs : false,
    refetchIntervalInBackground: false,
  })
}

export function useGithubDeepDiscoveryRepository(repositoryId: number | null, visible: boolean) {
  return useQuery({
    queryKey: [...DEEP_DISCOVERY_QUERY_KEY, 'repository', repositoryId],
    queryFn: ({ signal }) => githubDeepDiscoveryApi.repository(repositoryId!, signal),
    enabled: visible && repositoryId !== null,
  })
}

export function useGithubDeepDiscoveryProvenance(repositoryId: number | null, page: number, asOf: string | undefined, visible: boolean) {
  return useQuery({
    queryKey: [...DEEP_DISCOVERY_QUERY_KEY, 'repository', repositoryId, 'provenance', page, asOf],
    queryFn: ({ signal }) => githubDeepDiscoveryApi.provenance(repositoryId!, page, asOf, signal),
    enabled: visible && repositoryId !== null,
    staleTime: Infinity,
    refetchOnReconnect: false,
    refetchOnWindowFocus: false,
  })
}

export function useGithubDeepDiscoveryPaths(repositoryId: number | null, page: number, asOf: string | undefined, visible: boolean) {
  return useQuery({
    queryKey: [...DEEP_DISCOVERY_QUERY_KEY, 'repository', repositoryId, 'paths', page, asOf],
    queryFn: ({ signal }) => githubDeepDiscoveryApi.paths(repositoryId!, page, asOf, signal),
    enabled: visible && repositoryId !== null,
    staleTime: Infinity,
    refetchOnReconnect: false,
    refetchOnWindowFocus: false,
  })
}
