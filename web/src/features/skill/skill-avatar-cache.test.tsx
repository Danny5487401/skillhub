/** @vitest-environment jsdom */
import { QueryClient, QueryClientProvider, useQuery } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ status: vi.fn(), appeal: vi.fn(), queue: vi.fn(), decide: vi.fn() }))
vi.mock('@/api/client', () => ({ skillAvatarApi: api, buildApiUrl: (url: string) => url }))
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }))
import { SkillAvatarManager } from './skill-avatar-manager'
import { SkillAvatarReviewQueue } from './skill-avatar-review'

const ownerKey = ['skill-avatar', 'team', 'foo']
const queueKey = ['skill-avatar-review']
const pending = { namespace: 'team', slug: 'foo', avatarUrl: null, candidateId: 42, candidateStatus: 'PENDING', machineStatus: 'SKIPPED', reviewStatus: 'PENDING', candidateUrl: null }
const active = { ...pending, avatarUrl: '/active', candidateStatus: 'ACTIVE', reviewStatus: 'APPROVED' }

describe('avatar cache across owner and reviewer flows', () => {
  afterEach(() => { cleanup(); Object.values(api).forEach(fn => fn.mockReset()) })

  it('refreshes a previously empty fresh review queue when the owner appeals a blocked candidate', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
    client.setQueryData(ownerKey, { ...pending, candidateId: 20, machineStatus: 'BLOCKED', reviewStatus: 'NOT_REQUIRED' })
    client.setQueryData(queueKey, [])
    api.status.mockResolvedValue(pending)
    api.appeal.mockResolvedValue(pending)
    api.queue.mockResolvedValue([pending])
    render(<QueryClientProvider client={client}><SkillAvatarManager namespace="team" slug="foo" /><SkillAvatarReviewQueue namespace="team" /></QueryClientProvider>)
    expect(screen.getByText('skillAvatar.emptyQueue')).toBeTruthy()
    expect(api.queue).not.toHaveBeenCalled()

    fireEvent.click(screen.getByRole('button', { name: 'skillAvatar.appeal' }))

    expect(await screen.findByText('@team/foo')).toBeTruthy()
    expect(client.getQueryData(ownerKey)).toEqual(pending)
    expect(client.getQueryData(queueKey)).toEqual([pending])
  })

  it('refreshes the owner status and skill display after the owner approves their own candidate', async () => {
    const client = new QueryClient({ defaultOptions: { queries: { retry: false, staleTime: Infinity } } })
    const skillKey = ['skills', 'team', 'foo', 'en']
    client.setQueryData(ownerKey, pending)
    client.setQueryData(queueKey, [pending])
    client.setQueryData(skillKey, { avatarUrl: null })
    api.status.mockResolvedValue(active)
    api.queue.mockResolvedValue([])
    api.decide.mockResolvedValue(active)
    function OwnerView() {
      const status = useQuery({ queryKey: ownerKey, queryFn: () => api.status('team', 'foo') })
      const skill = useQuery({ queryKey: skillKey, queryFn: async () => ({ avatarUrl: '/active' }) })
      return <p data-testid="owner-view">{status.data?.candidateStatus}/{skill.data?.avatarUrl ?? 'fallback'}</p>
    }
    render(<QueryClientProvider client={client}><OwnerView /><SkillAvatarReviewQueue namespace="team" /></QueryClientProvider>)
    expect(screen.getByTestId('owner-view').textContent).toBe('PENDING/fallback')
    fireEvent.change(screen.getByLabelText('skillAvatar.reason'), { target: { value: 'Approved local image' } })
    fireEvent.click(screen.getByRole('button', { name: 'skillAvatar.approve' }))

    await waitFor(() => expect(screen.getByTestId('owner-view').textContent).toBe('ACTIVE//active'))
    expect(screen.queryByText('@team/foo')).toBeNull()
    expect(client.getQueryData(ownerKey)).toEqual(active)
    expect(client.getQueryData(queueKey)).toEqual([])
  })
})
