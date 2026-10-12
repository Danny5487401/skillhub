/** @vitest-environment jsdom */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  invitationStatus: vi.fn(),
  createInvitation: vi.fn(),
  revokeInvitation: vi.fn(),
}))

vi.mock('@/api/client', () => ({ namespaceApi: api }))
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }))

import { InvitationControls } from './invitation-controls'

describe('InvitationControls', () => {
  afterEach(() => {
    cleanup()
    Object.values(api).forEach((mock) => mock.mockReset())
  })

  async function openConfirmation(action: 'replace' | 'revoke') {
    api.invitationStatus.mockResolvedValue({ active: true })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={client}><InvitationControls slug="team" /></QueryClientProvider>)
    const button = await screen.findByRole('button', { name: action === 'replace' ? 'invitation.create' : 'invitation.revoke' })
    await waitFor(() => expect((button as HTMLButtonElement).disabled).toBe(false))
    fireEvent.click(button)
    return screen.findByRole('dialog')
  }

  it.each(['replace', 'revoke'] as const)('shows a reachable error after %s fails', async (action) => {
    api.createInvitation.mockRejectedValue(new Error('unavailable'))
    api.revokeInvitation.mockRejectedValue(new Error('unavailable'))
    const dialog = await openConfirmation(action)

    fireEvent.click(within(dialog).getByRole('button', { name: 'dialog.confirm' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(screen.getByRole('alert').textContent).toBe('invitation.error')
    expect(action === 'replace' ? api.createInvitation : api.revokeInvitation).toHaveBeenCalledTimes(1)
  })
})
