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
    const reviewsKey = ['reviews', 'PENDING', 7, 0, 20, 'DESC']
    client.setQueryData(reviewsKey, { items: [] })
    const loadReviews = vi.fn().mockResolvedValue({ items: [{ skillSlug: 'foo', version: '1.1.0' }] })
    const loadAvatar = vi.fn().mockResolvedValue({ avatarUrl: null })
    transport.fetchJson.mockResolvedValue({ namespace: 'team', slug: 'foo', version: '1.1.0', status: 'PENDING_REVIEW' })
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>
    const hook = renderHook(() => {
      useQuery({ queryKey: key, queryFn: loadAvatar, staleTime: Infinity })
      useQuery({ queryKey: reviewsKey, queryFn: loadReviews, staleTime: Infinity })
      return usePublishSkill()
    }, { wrapper })
    expect(loadAvatar).not.toHaveBeenCalled()

    await act(() => hook.result.current.mutateAsync({ namespace: 'team', file: new File(['zip'], 'skill.zip'), visibility: 'PUBLIC', avatarAction: 'REMOVE' }))

    await waitFor(() => expect(client.getQueryData(key)).toEqual({ avatarUrl: null }))
    expect(loadAvatar).toHaveBeenCalledOnce()
    expect(client.getQueryData(reviewsKey)).toEqual({ items: [{ skillSlug: 'foo', version: '1.1.0' }] })
    expect(client.getQueryData(['skill-avatar', 'other-team', 'bar'])).toEqual({ avatarUrl: '/unrelated' })
    expect(client.getQueryState(['skill-avatar', 'other-team', 'bar'])?.isInvalidated).toBe(false)
  })
  it.each(['REMOVE', 'KEEP', 'REPLACE'] as const)('uses %s refresh semantics for cached inactive MySkills and detail queries', async (avatarAction) => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
    const myKey = ['skills', 'my', { q: 'foo' }]
    const detailKey = ['skills', 'team', 'foo', 'en']
    const myLoader = vi.fn().mockResolvedValueOnce({ items: [{ slug: 'foo', avatarUrl: '/old-avatar' }] }).mockResolvedValue({ items: [{ slug: 'foo', avatarUrl: null }] })
    const detailLoader = vi.fn().mockResolvedValueOnce({ slug: 'foo', avatarUrl: '/old-avatar' }).mockResolvedValue({ slug: 'foo', avatarUrl: null })
    await client.fetchQuery({ queryKey: myKey, queryFn: myLoader })
    await client.fetchQuery({ queryKey: detailKey, queryFn: detailLoader })
    expect(client.getQueryCache().find({ queryKey: myKey })?.getObserversCount()).toBe(0)
    expect(client.getQueryCache().find({ queryKey: detailKey })?.getObserversCount()).toBe(0)
    transport.fetchJson.mockResolvedValue({ namespace: 'team', slug: 'foo', version: '1.1.0', status: 'PENDING_REVIEW' })
    const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={client}>{children}</QueryClientProvider>
    const hook = renderHook(() => usePublishSkill(), { wrapper })
    await act(() => hook.result.current.mutateAsync({ namespace: 'team', file: new File(['zip'], 'skill.zip'), visibility: 'PUBLIC', avatarAction }))
    expect(client.getQueryData(myKey)).toEqual({ items: [{ slug: 'foo', avatarUrl: avatarAction === 'REMOVE' ? null : '/old-avatar' }] })
    expect(client.getQueryData(detailKey)).toEqual({ slug: 'foo', avatarUrl: avatarAction === 'REMOVE' ? null : '/old-avatar' })
    expect(myLoader).toHaveBeenCalledTimes(avatarAction === 'REMOVE' ? 2 : 1)
    expect(detailLoader).toHaveBeenCalledTimes(avatarAction === 'REMOVE' ? 2 : 1)
  })

})
