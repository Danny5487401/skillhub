/** @vitest-environment jsdom */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'

const mocks = vi.hoisted(() => ({
  navigate: vi.fn(),
  preview: vi.fn(),
  accept: vi.fn(),
}))

vi.mock('@tanstack/react-router', () => ({ useNavigate: () => mocks.navigate }))
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }))
vi.mock('@/api/client', () => ({
  namespaceApi: { previewInvitation: mocks.preview, acceptInvitation: mocks.accept },
}))
vi.mock('@/features/auth/use-auth', () => ({
  useAuth: () => ({ user: { userId: 'guest', displayName: 'Guest' } }),
}))
vi.mock('@/shared/lib/api-error', () => ({
  ApiError: class ApiError extends Error {
    constructor(message: string, public status: number) { super(message) }
  },
}))

import { ApiError } from '@/shared/lib/api-error'
import { JoinPage } from './join'
import { captureInvitationFragment, storedInvitationToken } from '@/shared/lib/namespace-invitation'

const token = 'A'.repeat(43)

function renderJoin() {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  return { client, ...render(<QueryClientProvider client={client}><JoinPage /></QueryClientProvider>) }
}

describe('JoinPage', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    window.history.replaceState(null, '', '/')
    mocks.navigate.mockReset()
    mocks.preview.mockReset()
    mocks.accept.mockReset()
  })

  afterEach(cleanup)

  it.each([token, `invite=${token}`])('clears fragment %s and navigates once for an existing member', async fragment => {
    window.history.replaceState(null, '', `/join#${fragment}`)
    mocks.preview.mockResolvedValue({
      namespaceSlug: 'team', namespaceDisplayName: 'Team', namespaceAvatarUrl: null,
      outcome: 'ALREADY_MEMBER', expiresAt: '2026-10-13T00:00:00Z',
    })

    const view = renderJoin()

    await waitFor(() => expect(mocks.navigate).toHaveBeenCalledWith({
      to: '/space/$namespace', params: { namespace: 'team' }, replace: true,
    }))
    expect(mocks.preview).toHaveBeenCalledWith(token)
    expect(mocks.accept).not.toHaveBeenCalled()
    expect(window.sessionStorage.length).toBe(0)
    expect(window.location.hash).toBe('')
    view.rerender(<QueryClientProvider client={view.client}><JoinPage /></QueryClientProvider>)
    expect(mocks.navigate).toHaveBeenCalledTimes(1)
    expect(screen.queryByRole('button', { name: 'invitation.enter' })).toBeNull()
  })

  it('announces loading while preview is pending', () => {
    window.history.replaceState(null, '', `/join#${token}`)
    mocks.preview.mockImplementation(() => new Promise(() => {}))

    renderJoin()

    expect(screen.getByRole('status').textContent).toBe('invitation.loading')
  })

  it('announces a terminal invalid link', async () => {
    window.history.replaceState(null, '', `/join#${token}`)
    mocks.preview.mockRejectedValue(new ApiError('invalid', 404))

    renderJoin()

    expect((await screen.findByRole('alert')).textContent).toBe('invitation.invalid')
    expect(window.sessionStorage.length).toBe(0)
  })

  it('uses the named token for preview and only joins after explicit confirmation', async () => {
    window.history.replaceState(null, '', `/join#invite=${token}`)
    mocks.preview.mockResolvedValue({
      namespaceSlug: 'team', namespaceDisplayName: 'Team', namespaceAvatarUrl: null,
      outcome: 'CONFIRM_REQUIRED', expiresAt: '2026-10-13T00:00:00Z',
    })
    mocks.accept.mockResolvedValue({ namespaceSlug: 'team' })
    renderJoin()
    const confirm = await screen.findByRole('button', { name: 'invitation.confirm' })
    expect(window.location.hash).toBe('')
    expect(storedInvitationToken()).toBe(token)
    expect(mocks.preview).toHaveBeenCalledWith(token)
    expect(mocks.accept).not.toHaveBeenCalled()
    fireEvent.click(confirm)
    await waitFor(() => expect(mocks.accept).toHaveBeenCalledWith(token))
    await waitFor(() => expect(mocks.navigate).toHaveBeenCalledWith({
      to: '/space/$namespace', params: { namespace: 'team' }, replace: true,
    }))
    expect(storedInvitationToken()).toBeNull()
  })

  it('rejects a new malformed named fragment instead of joining with a stored older token', () => {
    window.history.replaceState(null, '', `/join#invite=${token}`)
    captureInvitationFragment()
    window.history.replaceState(null, '', '/join#invite=invalid')
    renderJoin()
    expect(screen.getByRole('alert').textContent).toBe('invitation.invalid')
    expect(storedInvitationToken()).toBeNull()
    expect(window.location.hash).toBe('')
    expect(mocks.preview).not.toHaveBeenCalled()
    expect(mocks.accept).not.toHaveBeenCalled()
  })

})
