// @vitest-environment jsdom

import { createElement, type ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  repositories: vi.fn().mockResolvedValue({ items: [], total: 0, page: 0, size: 20, asOf: '2026-10-13T00:00:00Z' }),
  repository: vi.fn().mockResolvedValue({ repository: { repositoryId: 12 } }),
  repositoryOrigins: vi.fn().mockResolvedValue({ items: [], total: 0, page: 0, size: 20 }),
  repositoryPaths: vi.fn().mockResolvedValue({ items: [], total: 0, page: 0, size: 20 }),
}))

vi.mock('@/api/client', () => ({ githubSyncAdminApi: api }))

import { useGithubSyncRepositories, useGithubSyncRepository, useGithubSyncRepositoryOrigins, useGithubSyncRepositoryPaths } from './use-github-sync-admin'

function wrapper(client: QueryClient) {
  return function Provider({ children }: { children: ReactNode }) {
    return createElement(QueryClientProvider, { client }, children)
  }
}

function client() {
  return new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } })
}

describe('unified repository queries', () => {
  it('keeps the current page when hidden and only refreshes on an explicit version', async () => {
    api.repositories.mockClear()
    const queryClient = client()
    const { rerender, unmount } = renderHook(
      ({ visible, version }) => useGithubSyncRepositories({ page: 2, size: 20, asOf: '2026-10-13T00:00:00Z' }, visible, version),
      { initialProps: { visible: true, version: 0 }, wrapper: wrapper(queryClient) },
    )
    await vi.waitFor(() => expect(api.repositories).toHaveBeenCalledTimes(1))
    rerender({ visible: false, version: 0 })
    rerender({ visible: true, version: 0 })
    await act(() => new Promise((resolve) => setTimeout(resolve, 30)))
    expect(api.repositories).toHaveBeenCalledTimes(1)
    expect(api.repositories.mock.calls[0]?.[0]).toMatchObject({ page: 2, asOf: '2026-10-13T00:00:00Z' })
    rerender({ visible: true, version: 1 })
    await vi.waitFor(() => expect(api.repositories).toHaveBeenCalledTimes(2))
    unmount()
    queryClient.clear()
  })

  it('does not load drawer details until opened', async () => {
    api.repository.mockClear()
    api.repositoryOrigins.mockClear()
    api.repositoryPaths.mockClear()
    const queryClient = client()
    const { rerender, unmount } = renderHook(
      ({ visible }) => {
        useGithubSyncRepository(12, visible)
        useGithubSyncRepositoryOrigins(12, 0, undefined, visible)
        useGithubSyncRepositoryPaths(12, 0, undefined, visible)
      },
      { initialProps: { visible: false }, wrapper: wrapper(queryClient) },
    )
    expect(api.repository).not.toHaveBeenCalled()
    expect(api.repositoryOrigins).not.toHaveBeenCalled()
    expect(api.repositoryPaths).not.toHaveBeenCalled()
    rerender({ visible: true })
    await vi.waitFor(() => {
      expect(api.repository).toHaveBeenCalledTimes(1)
      expect(api.repositoryOrigins).toHaveBeenCalledTimes(1)
      expect(api.repositoryPaths).toHaveBeenCalledTimes(1)
    })
    unmount()
    queryClient.clear()
  })
})
