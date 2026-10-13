/** @vitest-environment jsdom */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ status: vi.fn(), delete: vi.fn(), submit: vi.fn(), retry: vi.fn(), appeal: vi.fn() }))
vi.mock('@/api/client', () => ({ skillAvatarApi: api, buildApiUrl: (url: string) => url }))
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }))
import { SkillAvatarManager } from './skill-avatar-manager'

describe('SkillAvatarManager appeal', () => {
  afterEach(() => { cleanup(); Object.values(api).forEach(fn => fn.mockReset()) })

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
    expect(await screen.findByText(new RegExp(`skillAvatar.states.${candidateStatus}`))).toBeTruthy()
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
  it('calls the existing retry API and preserves the recoverable state on failure', async () => {
    api.status.mockResolvedValue({ candidateId: 7, candidateStatus: 'PENDING', machineStatus: 'ERROR', reviewStatus: 'NOT_REQUIRED', machineNextRetryAt: null, automaticRetryExhausted: true })
    api.retry.mockRejectedValue(new Error('service unavailable'))
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={client}><SkillAvatarManager namespace="team" slug="foo" /></QueryClientProvider>)
    fireEvent.click(await screen.findByRole('button', { name: 'skillAvatar.retry' }))
    expect((await screen.findByRole('alert')).textContent).toBe('service unavailable')
    expect(api.retry).toHaveBeenCalledWith('team', 'foo', 7)
    expect(screen.getByRole('button', { name: 'skillAvatar.retry' }).hasAttribute('disabled')).toBe(false)
  })

  it('does not render approved reasons, duplicate image or editing controls', async () => {
    api.status.mockResolvedValue({ avatarUrl: '/active', candidateId: 7, candidateStatus: 'ACTIVE', machineStatus: 'SKIPPED', reviewStatus: 'APPROVED', reviewReason: 'approved reason' })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={client}><SkillAvatarManager namespace="team" slug="foo" /></QueryClientProvider>)
    await waitFor(() => expect(api.status).toHaveBeenCalled())
    expect(screen.queryByText('approved reason')).toBeNull()
    expect(screen.queryByRole('img')).toBeNull()
    expect(screen.queryByRole('button')).toBeNull()
  })
})
