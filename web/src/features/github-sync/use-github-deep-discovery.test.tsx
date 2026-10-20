// @vitest-environment jsdom

import { createElement, type ReactNode } from 'react'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { act, renderHook } from '@testing-library/react'
import { describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  repositories: vi.fn().mockResolvedValue({ items: [], total: 0, page: 0, size: 20, asOf: '2026-10-13T00:00:00Z' }),
  provenance: vi.fn().mockResolvedValue({ items: [], total: 0, page: 0, size: 20, asOf: '2026-10-13T00:00:00Z' }),
  paths: vi.fn().mockResolvedValue({ items: [], total: 0, page: 0, size: 20, asOf: '2026-10-13T00:00:00Z' }),
}))

vi.mock('@/api/client', () => ({ githubDeepDiscoveryApi: api }))

import {
  useGithubDeepDiscoveryPaths,
  useGithubDeepDiscoveryProvenance,
  useGithubDeepDiscoveryRepositories,
} from './use-github-deep-discovery'

function wrapper(client: QueryClient) {
  return function Provider({ children }: { children: ReactNode }) {
    return createElement(QueryClientProvider, { client }, children)
  }
}

function client() {
  return new QueryClient({ defaultOptions: { queries: { retry: false, gcTime: Infinity } } })
}

describe('deep discovery static pages', () => {
  it('keeps the repository page when hidden then visible, and refreshes only on a new version', async () => {
    api.repositories.mockClear()
    const queryClient = client()
    const { rerender, unmount } = renderHook(
      ({ visible, version }) => useGithubDeepDiscoveryRepositories({ page: 0, size: 20 }, visible, version),
      { initialProps: { visible: true, version: 0 }, wrapper: wrapper(queryClient) },
    )
    await vi.waitFor(() => expect(api.repositories).toHaveBeenCalledTimes(1))
    rerender({ visible: false, version: 0 })
    rerender({ visible: true, version: 0 })
    await act(() => new Promise((resolve) => setTimeout(resolve, 30)))
    expect(api.repositories).toHaveBeenCalledTimes(1)

    rerender({ visible: true, version: 1 })
    await vi.waitFor(() => expect(api.repositories).toHaveBeenCalledTimes(2))
    unmount()
    queryClient.clear()
  })

  it('keeps provenance and path result pages when the drawer becomes visible again', async () => {
    api.provenance.mockClear()
    api.paths.mockClear()
    const queryClient = client()
    const { rerender, unmount } = renderHook(
      ({ visible }) => {
        useGithubDeepDiscoveryProvenance(12, 0, undefined, visible)
        useGithubDeepDiscoveryPaths(12, 0, undefined, visible)
      },
      { initialProps: { visible: true }, wrapper: wrapper(queryClient) },
    )
    await vi.waitFor(() => {
      expect(api.provenance).toHaveBeenCalledTimes(1)
      expect(api.paths).toHaveBeenCalledTimes(1)
    })
    rerender({ visible: false })
    rerender({ visible: true })
    await act(() => new Promise((resolve) => setTimeout(resolve, 30)))
    expect(api.provenance).toHaveBeenCalledTimes(1)
    expect(api.paths).toHaveBeenCalledTimes(1)
    unmount()
    queryClient.clear()
  })
})
