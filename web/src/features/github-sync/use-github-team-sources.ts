import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { githubTeamSourcesApi } from '@/api/client'

const STATUS_REFRESH_MS = 30_000

export function useGithubTeamEntries(slug: string) {
  return useQuery({
    queryKey: ['github-sync', 'team', slug, 'entries'],
    queryFn: () => githubTeamSourcesApi.entries(slug),
    refetchInterval: STATUS_REFRESH_MS,
  })
}

export function useGithubTeamResolved(slug: string, page: number, size: number) {
  return useQuery({
    queryKey: ['github-sync', 'team', slug, 'resolved', page, size],
    queryFn: () => githubTeamSourcesApi.resolved(slug, page, size),
    refetchInterval: STATUS_REFRESH_MS,
  })
}

export function useGithubTeamRuns(slug: string, page: number, size: number) {
  return useQuery({
    queryKey: ['github-sync', 'team', slug, 'runs', page, size],
    queryFn: () => githubTeamSourcesApi.runs(slug, page, size),
    refetchInterval: STATUS_REFRESH_MS,
  })
}

export function useGithubTeamSkillPaths(slug: string, sourceId: number | null, page: number, size: number) {
  return useQuery({
    queryKey: ['github-sync', 'team', slug, 'resolved', sourceId, 'skills', page, size],
    queryFn: () => githubTeamSourcesApi.skillPaths(slug, sourceId!, page, size),
    enabled: sourceId !== null,
    refetchInterval: STATUS_REFRESH_MS,
  })
}

export function useGithubTeamSourceMutations(slug: string) {
  const queryClient = useQueryClient()
  const onSuccess = () => {
    queryClient.invalidateQueries({ queryKey: ['github-sync', 'team', slug, 'entries'] })
    queryClient.invalidateQueries({ queryKey: ['github-sync', 'team', slug, 'resolved'] })
  }
  return {
    create: useMutation({
      mutationFn: ({ ownerLogin, repositoryName, visibility }: { ownerLogin: string; repositoryName: string; visibility: string }) =>
        githubTeamSourcesApi.create(slug, ownerLogin, repositoryName, visibility),
      onSuccess,
    }),
    setEnabled: useMutation({
      mutationFn: ({ entryId, enabled }: { entryId: string; enabled: boolean }) =>
        githubTeamSourcesApi.setEnabled(slug, entryId, enabled),
      onSuccess,
    }),
    setVisibility: useMutation({
      mutationFn: ({ entryId, visibility }: { entryId: string; visibility: string }) =>
        githubTeamSourcesApi.setVisibility(slug, entryId, visibility),
      onSuccess,
    }),
  }
}
