import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { githubSyncAdminApi, type GithubPlatformControlUpdateInput, type GithubSearchRuleInput, type GithubSyncAdmissionPolicyInput, type GithubSyncRepositoryQuery, type GithubSyncRunQuery, type GithubSyncSchedulePolicyInput } from '@/api/client'

const STATUS_REFRESH_MS = 30_000
const DETAIL_PAGE_SIZE = 20

export function useGithubPlatformControl(enabled: boolean) {
  return useQuery({
    queryKey: ['github-sync', 'platform-control'],
    queryFn: ({ signal }) => githubSyncAdminApi.platformControl(signal),
    enabled,
    refetchInterval: (query) => enabled ? query.state.data?.state === 'PAUSING' ? 5_000 : STATUS_REFRESH_MS : false,
    refetchIntervalInBackground: false,
  })
}

export function useUpdateGithubPlatformControl() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: GithubPlatformControlUpdateInput) => githubSyncAdminApi.updatePlatformControl(body),
    onSuccess: () => void queryClient.invalidateQueries({ queryKey: ['github-sync', 'platform-control'] }),
  })
}

export function useGithubSyncWorkerStatus(enabled = true) {
  return useQuery({
    queryKey: ['github-sync', 'worker-status'],
    queryFn: ({ signal }) => githubSyncAdminApi.workerStatus(signal),
    enabled,
    refetchInterval: enabled ? STATUS_REFRESH_MS : false,
  })
}

export function useGithubSyncSchedulePolicy(enabled = true) {
  return useQuery({
    queryKey: ['github-sync', 'schedule-policy'],
    queryFn: ({ signal }) => githubSyncAdminApi.schedulePolicy(signal),
    enabled,
  })
}

export function useUpdateGithubSyncSchedulePolicy() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: GithubSyncSchedulePolicyInput) => githubSyncAdminApi.updateSchedulePolicy(body),
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: ['github-sync', 'schedule-policy'] })
      void queryClient.invalidateQueries({ queryKey: ['github-sync', 'discovery-entries'] })
      void queryClient.invalidateQueries({ queryKey: ['github-sync', 'sources'] })
    },
  })
}

export function useGithubSyncSources(page: number, size: number, enabled = true) {
  return useQuery({
    queryKey: ['github-sync', 'sources', page, size],
    queryFn: ({ signal }) => githubSyncAdminApi.sources(page, size, signal),
    enabled,
    refetchInterval: enabled ? STATUS_REFRESH_MS : false,
  })
}

export function useGithubSyncRepositories(query: GithubSyncRepositoryQuery, enabled: boolean, refreshVersion = 0, refreshIntervalMs?: number) {
  return useQuery({
    queryKey: ['github-sync', 'repositories', query, refreshVersion],
    queryFn: ({ signal }) => githubSyncAdminApi.repositories(query, signal),
    enabled,
    staleTime: refreshIntervalMs ? 0 : Infinity,
    refetchOnReconnect: false,
    refetchOnWindowFocus: false,
    refetchInterval: enabled && refreshIntervalMs ? refreshIntervalMs : false,
    refetchIntervalInBackground: false,
  })
}

export function useGithubSyncRepository(repositoryId: number, enabled: boolean) {
  return useQuery({
    queryKey: ['github-sync', 'repository', repositoryId],
    queryFn: ({ signal }) => githubSyncAdminApi.repository(repositoryId, signal),
    enabled,
  })
}

export function useGithubSyncRepositoryOrigins(repositoryId: number, page: number, asOf: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: ['github-sync', 'repository', repositoryId, 'origins', page, asOf],
    queryFn: ({ signal }) => githubSyncAdminApi.repositoryOrigins(repositoryId, page, DETAIL_PAGE_SIZE, asOf, signal),
    enabled,
    staleTime: Infinity,
    refetchOnReconnect: false,
    refetchOnWindowFocus: false,
  })
}

export function useGithubSyncRepositoryPaths(repositoryId: number, page: number, asOf: string | undefined, enabled: boolean) {
  return useQuery({
    queryKey: ['github-sync', 'repository', repositoryId, 'paths', page, asOf],
    queryFn: ({ signal }) => githubSyncAdminApi.repositoryPaths(repositoryId, page, DETAIL_PAGE_SIZE, asOf, signal),
    enabled,
    staleTime: Infinity,
    refetchOnReconnect: false,
    refetchOnWindowFocus: false,
  })
}

export function useGithubSyncRuns(page: number, size: number, enabled = true, kind?: GithubSyncRunQuery['kind'], status?: GithubSyncRunQuery['status']) {
  return useQuery({
    queryKey: ['github-sync', 'runs', page, size, kind, status],
    queryFn: ({ signal }) => githubSyncAdminApi.runs(page, size, signal, kind, status),
    enabled,
    refetchInterval: enabled ? STATUS_REFRESH_MS : false,
  })
}

export function useGithubSyncRunDetail(runId: string, enabled = true) {
  return useQuery({
    queryKey: ['github-sync', 'runs', runId, 'detail'],
    queryFn: ({ signal }) => githubSyncAdminApi.runDetail(runId, signal),
    enabled,
    refetchInterval: enabled ? STATUS_REFRESH_MS : false,
  })
}

export function useGithubSyncRunTasks(runId: string, page: number, size: number, enabled = true) {
  return useQuery({
    queryKey: ['github-sync', 'runs', runId, 'tasks', page, size],
    queryFn: ({ signal }) => githubSyncAdminApi.runTasks(runId, page, size, signal),
    enabled,
    refetchInterval: enabled ? STATUS_REFRESH_MS : false,
  })
}

export function useGithubSyncRunPaths(runId: string, page: number, size: number, enabled = true) {
  return useQuery({
    queryKey: ['github-sync', 'runs', runId, 'paths', page, size],
    queryFn: ({ signal }) => githubSyncAdminApi.runPaths(runId, page, size, signal),
    enabled,
    refetchInterval: enabled ? STATUS_REFRESH_MS : false,
  })
}

export function useGithubSyncSkillPaths(sourceId: number | null, page: number, size: number, enabled = true) {
  return useQuery({
    queryKey: ['github-sync', 'sources', sourceId, 'skills', page, size],
    queryFn: ({ signal }) => githubSyncAdminApi.skillPaths(sourceId!, page, size, signal),
    enabled: enabled && sourceId !== null,
    refetchInterval: enabled ? STATUS_REFRESH_MS : false,
  })
}

export function useGithubSyncAdmissionPolicy(enabled = true) {
  return useQuery({
    queryKey: ['github-sync', 'admission-policy'],
    queryFn: ({ signal }) => githubSyncAdminApi.admissionPolicy(signal),
    enabled,
    refetchInterval: enabled ? STATUS_REFRESH_MS : false,
  })
}

export function useUpdateGithubSyncAdmissionPolicy() {
  const queryClient = useQueryClient()
  return useMutation({
    mutationFn: (body: GithubSyncAdmissionPolicyInput) => githubSyncAdminApi.updateAdmissionPolicy(body),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['github-sync', 'admission-policy'] }),
  })
}

export function useGithubDiscoveryEntries(enabled = true) {
  return useQuery({
    queryKey: ['github-sync', 'discovery-entries'],
    queryFn: ({ signal }) => githubSyncAdminApi.discoveryEntries(signal),
    enabled,
    refetchInterval: enabled ? STATUS_REFRESH_MS : false,
  })
}

export function useGithubSearchPreview(id: string | null) {
  return useQuery({
    queryKey: ['github-sync', 'search-preview', id],
    queryFn: () => githubSyncAdminApi.searchRulePreview(id!),
    enabled: id !== null,
    refetchInterval: (query) => {
      const status = query.state.data?.status
      return status && ['ACQUISITION_COMPLETE', 'FAILED'].includes(status) ? false : 3000
    },
  })
}

export function useGithubDiscoveryMutations() {
  const queryClient = useQueryClient()
  const onSuccess = () => queryClient.invalidateQueries({ queryKey: ['github-sync', 'discovery-entries'] })
  return {
    addRepository: useMutation({
      mutationFn: ({ ownerLogin, repositoryName }: { ownerLogin: string; repositoryName: string }) =>
        githubSyncAdminApi.addRepository(ownerLogin, repositoryName),
      onSuccess,
    }),
    addOwner: useMutation({
      mutationFn: ({ ownerLogin, targetHours }: { ownerLogin: string; targetHours?: number }) =>
        githubSyncAdminApi.addOwner(ownerLogin, targetHours),
      onSuccess,
    }),
    addSearchRule: useMutation({
      mutationFn: (body: GithubSearchRuleInput) => githubSyncAdminApi.addSearchRule(body),
      onSuccess,
    }),
    updateSearchRule: useMutation({
      mutationFn: ({ id, body }: { id: string; body: GithubSearchRuleInput }) =>
        githubSyncAdminApi.updateSearchRule(id, body),
      onSuccess,
    }),
    setEnabled: useMutation({
      mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) => githubSyncAdminApi.setDiscoveryEnabled(id, enabled),
      onSuccess,
    }),
  }
}

export function useGithubCredentials(enabled = true) {
  return useQuery({
    queryKey: ['github-sync', 'credentials'],
    queryFn: ({ signal }) => githubSyncAdminApi.credentials(signal),
    enabled,
    refetchInterval: enabled ? STATUS_REFRESH_MS : false,
  })
}

export function useGithubCredentialMutations() {
  const queryClient = useQueryClient()
  const onSuccess = () => queryClient.invalidateQueries({ queryKey: ['github-sync', 'credentials'] })
  return {
    create: useMutation({
      mutationFn: ({ label, token }: { label: string; token: string }) =>
        githubSyncAdminApi.createCredential(label, token),
      onSuccess,
    }),
    replace: useMutation({
      mutationFn: ({ id, label, token }: { id: string; label: string; token: string }) =>
        githubSyncAdminApi.replaceCredential(id, label, token),
      onSuccess,
    }),
    setEnabled: useMutation({
      mutationFn: ({ id, enabled }: { id: string; enabled: boolean }) =>
        githubSyncAdminApi.setCredentialEnabled(id, enabled),
      onSuccess,
    }),
  }
}
