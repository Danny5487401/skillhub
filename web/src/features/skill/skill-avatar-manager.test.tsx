/** @vitest-environment jsdom */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ status: vi.fn() }))
vi.mock('@/api/client', () => ({ skillAvatarApi: api, buildApiUrl: (url: string) => url }))
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }))
import { SkillAvatarManager } from './skill-avatar-manager'

describe('SkillAvatarManager appeal', () => {
  afterEach(() => { cleanup(); api.status.mockReset() })

  it.each([
    ['PENDING', 'BLOCKED', 'NOT_REQUIRED', true],
    ['PENDING', 'BLOCKED', 'APPEAL_PENDING', false],
    ['PENDING', 'PROCESSING', 'NOT_REQUIRED', false],
    ['REJECTED', 'BLOCKED', 'REJECTED', false],
    ['PENDING', 'ERROR', 'NOT_REQUIRED', false],
  ] as const)('shows appeal only for machine-only blocked candidate: %s/%s/%s', async (candidateStatus, machineStatus, reviewStatus, expected) => {
    api.status.mockResolvedValue({ avatarUrl: null, candidateId: 7, candidateStatus, machineStatus, reviewStatus, candidateUrl: null, reviewReason: null })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={client}><SkillAvatarManager namespace="team" slug="foo" /></QueryClientProvider>)
    expect((await screen.findByRole('status')).textContent).toContain(`skillAvatar.states.${candidateStatus}`)
    expect(screen.queryByRole('button', { name: 'skillAvatar.appeal' }) !== null).toBe(expected)
  })

  it('disables paid retry during cooldown and explains when automatic retries stop', async () => {
    api.status.mockResolvedValue({ avatarUrl: null, candidateId: 7, candidateStatus: 'PENDING',
      machineStatus: 'ERROR', reviewStatus: 'NOT_REQUIRED', candidateUrl: null, reviewReason: null,
      machineNextRetryAt: new Date(Date.now() + 3600000).toISOString(), automaticRetryExhausted: true })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={client}><SkillAvatarManager namespace="team" slug="foo" /></QueryClientProvider>)

    expect(await screen.findByText('skillAvatar.retryAfter')).toBeTruthy()
    expect(screen.getByText('skillAvatar.automaticRetryStopped')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'skillAvatar.retry' }).hasAttribute('disabled')).toBe(true)
  })
})
