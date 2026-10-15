/** @vitest-environment jsdom */
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ status: vi.fn(), appeal: vi.fn(), queue: vi.fn(), approve: vi.fn() }))
vi.mock('@/api/client', () => ({ skillAvatarApi: api, reviewApi: { approve: api.approve }, buildApiUrl: (url: string) => url }))
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }))
import { SkillAvatarManager } from './skill-avatar-manager'
import { useApproveReview } from '@/features/review/use-review-detail'

const ownerKey = ['skill-avatar', 'team', 'foo']
const queueKey = ['reviews', 'PENDING', 7]
const pending = { namespace: 'team', slug: 'foo', avatarUrl: null, candidateId: 42, candidateStatus: 'PENDING', machineStatus: 'SKIPPED', reviewStatus: 'PENDING', candidateUrl: null }
const active = { ...pending, avatarUrl: '/active', candidateStatus: 'ACTIVE', reviewStatus: 'APPROVED' }
function UnifiedQueue() {
  const queue = useQuery({ queryKey: queueKey, queryFn: () => api.queue() })
  const decision = useApproveReview()
  return <div>{queue.data?.map((task: { id: number; skillSlug: string }) => <button key={task.id} onClick={() => decision.mutate({ taskId: task.id })}>approve {task.skillSlug}</button>)}</div>
}

describe('avatar cache across unified owner and reviewer flows', () => {
  afterEach(() => { cleanup(); Object.values(api).forEach(fn => fn.mockReset()) })

  it('refreshes the existing task queue when the owner appeals a blocked candidate', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
    client.setQueryData(ownerKey, { ...pending, candidateId: 20, machineStatus: 'BLOCKED', reviewStatus: 'NOT_REQUIRED' })
    client.setQueryData(queueKey, [])
    api.status.mockResolvedValue(pending)
    api.appeal.mockResolvedValue(pending)
    api.queue.mockResolvedValue([{ id: 10, skillSlug: 'foo', subjectType: 'SKILL_METADATA' }])
    render(<QueryClientProvider client={client}><SkillAvatarManager namespace="team" slug="foo" /><UnifiedQueue /></QueryClientProvider>)
    expect(api.queue).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'skillAvatar.appeal' }))
    expect(await screen.findByRole('button', { name: 'approve foo' })).toBeTruthy()
    expect(client.getQueryData(ownerKey)).toEqual(pending)
  })

  it('uses standard task approval to refresh owner state and cached skill avatars', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
    const skillKey = ['skills', 'team', 'foo', 'en']
    client.setQueryData(ownerKey, pending)
    client.setQueryData(queueKey, [{ id: 10, skillSlug: 'foo' }])
    client.setQueryData(skillKey, { avatarUrl: null })
    api.status.mockResolvedValue(active)
    api.queue.mockResolvedValue([])
    api.approve.mockResolvedValue(undefined)
    function OwnerView() {
      const status = useQuery({ queryKey: ownerKey, queryFn: () => api.status('team', 'foo') })
      const skill = useQuery({ queryKey: skillKey, queryFn: async () => ({ avatarUrl: '/active' }) })
      return <p data-testid="owner-view">{status.data?.candidateStatus}/{skill.data?.avatarUrl ?? 'fallback'}</p>
    }
    render(<QueryClientProvider client={client}><OwnerView /><UnifiedQueue /></QueryClientProvider>)
    expect(screen.getByTestId('owner-view').textContent).toBe('PENDING/fallback')
    fireEvent.click(screen.getByRole('button', { name: 'approve foo' }))
    await waitFor(() => expect(screen.getByTestId('owner-view').textContent).toBe('ACTIVE//active'))
    expect(api.approve).toHaveBeenCalledWith(10, undefined)
    expect(client.getQueryData(queueKey)).toEqual([])
    expect(client.getQueryData(ownerKey)).toEqual(active)
  })
})
