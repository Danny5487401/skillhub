/** @vitest-environment jsdom */
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import { act, cleanup, renderHook, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'

const transport = vi.hoisted(() => ({ fetchJson: vi.fn() }))
vi.mock('@/api/client', async () => ({ ...await vi.importActual<typeof import('@/api/client')>('@/api/client'), fetchJson: transport.fetchJson, getCsrfHeaders: () => ({}) }))
import { usePublishSkill } from './use-publish-skill'

describe('publishing refreshes owner avatar state', () => {
  afterEach(() => { cleanup(); transport.fetchJson.mockReset() })

  it('refreshes the active owner avatar query before the successful update returns', async () => {
    const key = ['skill-avatar', 'team', 'foo']
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    client.setQueryData(key, { avatarUrl: '/old-avatar' })
    client.setQueryData(['skill-avatar', 'other-team', 'bar'], { avatarUrl: '/unrelated' })
    client.setQueryData(['skill-avatar-review'], [])
    const reviewsKey = ['reviews', 'PENDING', 7, 0, 20, 'DESC']
    client.setQueryData(reviewsKey, { items: [] })
    const loadReviews = vi.fn().mockResolvedValue({ items: [{ skillSlug: 'foo', version: '1.1.0' }] })
    const loadQueue = vi.fn().mockResolvedValue([{ candidateId: 42 }])
    const loadAvatar = vi.fn().mockResolvedValue({ avatarUrl: null })
    transport.fetchJson.mockResolvedValue({ namespace: 'team', slug: 'foo', version: '1.1.0', status: 'PENDING_REVIEW' })
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>
    const hook = renderHook(() => {
      useQuery({ queryKey: key, queryFn: loadAvatar, staleTime: Infinity })
      useQuery({ queryKey: ['skill-avatar-review'], queryFn: loadQueue, staleTime: Infinity })
      useQuery({ queryKey: reviewsKey, queryFn: loadReviews, staleTime: Infinity })
      return usePublishSkill()
    }, { wrapper })
    expect(loadAvatar).not.toHaveBeenCalled()

    await act(() => hook.result.current.mutateAsync({ namespace: 'team', file: new File(['zip'], 'skill.zip'), visibility: 'PUBLIC', avatarAction: 'REMOVE' }))

    await waitFor(() => expect(client.getQueryData(key)).toEqual({ avatarUrl: null }))
    expect(loadAvatar).toHaveBeenCalledOnce()
    expect(client.getQueryData(['skill-avatar-review'])).toEqual([{ candidateId: 42 }])
    expect(client.getQueryData(reviewsKey)).toEqual({ items: [{ skillSlug: 'foo', version: '1.1.0' }] })
    expect(client.getQueryData(['skill-avatar', 'other-team', 'bar'])).toEqual({ avatarUrl: '/unrelated' })
    expect(client.getQueryState(['skill-avatar', 'other-team', 'bar'])?.isInvalidated).toBe(false)
  })
})
