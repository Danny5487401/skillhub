/** @vitest-environment jsdom */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({
  invitationStatus: vi.fn(),
  invitationHistory: vi.fn().mockResolvedValue({ items: [], total: 0, totalPages: 0, page: 0, size: 10 }),
  createInvitation: vi.fn(),
  revokeInvitation: vi.fn(),
  previewInvitation: vi.fn(),
}))

vi.mock('@/api/client', () => ({ namespaceApi: api, getAppBaseUrl: () => undefined }))
const auth = vi.hoisted(() => ({ user: { userId: 'owner' } }))
vi.mock('@/features/auth/use-auth', () => ({ useAuth: () => auth }))
vi.mock('@/shared/lib/toast', () => ({ toast: { success: vi.fn(), info: vi.fn() } }))
vi.mock('@/i18n/config', () => ({ default: { exists: () => false } }))
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }))

import { ApiError } from '@/shared/lib/api-error'
import { InvitationControls } from './invitation-controls'

describe('InvitationControls', () => {
  afterEach(() => {
    cleanup()
    vi.restoreAllMocks()
    window.sessionStorage.clear()
    auth.user = { userId: 'owner' }
    Object.values(api).forEach((mock) => mock.mockReset())
    api.invitationHistory.mockResolvedValue({ items: [], total: 0, totalPages: 0, page: 0, size: 10 })
  })

  async function openConfirmation(action: 'replace' | 'revoke') {
    api.invitationStatus.mockResolvedValue({ active: true })
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={client}><InvitationControls slug="team" /></QueryClientProvider>)
    const button = await screen.findByRole('button', { name: action === 'replace' ? 'invitation.replaceAction' : 'invitation.revoke' })
    await waitFor(() => expect((button as HTMLButtonElement).disabled).toBe(false))
    fireEvent.click(button)
    return screen.findByRole('dialog')
  }

  it.each(['replace', 'revoke'] as const)('shows a reachable error after %s fails', async (action) => {
    api.createInvitation.mockRejectedValue(new Error('unavailable'))
    api.revokeInvitation.mockRejectedValue(new Error('unavailable'))
    const dialog = await openConfirmation(action)
    if (action === 'replace') {
      expect(api.invitationStatus).toHaveBeenCalledTimes(2)
      expect(api.createInvitation).not.toHaveBeenCalled()
    }

    fireEvent.click(within(dialog).getByRole('button', { name: 'dialog.confirm' }))

    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(screen.getByRole('alert').textContent).toBe('invitation.error')
    expect(action === 'replace' ? api.createInvitation : api.revokeInvitation).toHaveBeenCalledTimes(1)
  })

  it.each(['consumed', 'expired'])('creates without a replacement prompt when the cached link was %s', async () => {
    api.invitationStatus.mockResolvedValueOnce({ active: true }).mockResolvedValue({ active: false })
    api.createInvitation.mockImplementation(() => new Promise(() => {}))
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={client}><InvitationControls slug="team" /></QueryClientProvider>)
    const createButton = await screen.findByRole('button', { name: 'invitation.replaceAction' })
    await waitFor(() => expect((createButton as HTMLButtonElement).disabled).toBe(false))
    expect(screen.getByRole('button', { name: 'invitation.revoke' })).toBeTruthy()

    fireEvent.click(createButton)

    await waitFor(() => expect(api.createInvitation).toHaveBeenCalledWith('team', false))
    expect(api.invitationStatus).toHaveBeenCalledTimes(2)
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('does not create or confirm replacement when the fresh status fails despite cached active data', async () => {
    api.invitationStatus.mockResolvedValueOnce({ active: true }).mockRejectedValue(new Error('unavailable'))
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={client}><InvitationControls slug="team" /></QueryClientProvider>)
    const createButton = await screen.findByRole('button', { name: 'invitation.replaceAction' })
    await waitFor(() => expect((createButton as HTMLButtonElement).disabled).toBe(false))

    fireEvent.click(createButton)

    await screen.findByRole('alert')
    expect(api.invitationStatus).toHaveBeenCalledTimes(2)
    expect(api.createInvitation).not.toHaveBeenCalled()
    expect(api.revokeInvitation).not.toHaveBeenCalled()
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it.each(['create', 'replace', 'revoke'] as const)('refreshes history only after %s succeeds', async (action) => {
    api.invitationStatus.mockResolvedValue({ active: action !== 'create' })
    api.previewInvitation.mockResolvedValue({ namespaceSlug: 'team', expiresAt: '2999-01-01T00:00:00Z' })
    const historyPage = { items: [], page: 0, size: 10, total: 0, totalPages: 0 }
    api.invitationHistory.mockResolvedValue(historyPage)
    let succeed: () => void = () => {}
    if (action === 'revoke') api.revokeInvitation.mockImplementation(() => new Promise<void>((resolve) => { succeed = () => { api.invitationStatus.mockResolvedValue({ active: false }); resolve() } }))
    else api.createInvitation.mockImplementation(() => new Promise((resolve) => { succeed = () => { api.invitationStatus.mockResolvedValue({ active: true }); resolve({ token: 'a'.repeat(43), replaced: action === 'replace' }) } }))
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    render(<QueryClientProvider client={client}><InvitationControls slug="team" /></QueryClientProvider>)
    const button = await screen.findByRole('button', { name: action === 'create' ? 'invitation.create' : action === 'replace' ? 'invitation.replaceAction' : 'invitation.revoke' })
    await waitFor(() => expect((button as HTMLButtonElement).disabled).toBe(false))
    await waitFor(() => expect(api.invitationHistory).toHaveBeenCalledTimes(1))
    fireEvent.click(button)
    if (action !== 'create') {
      const dialog = await screen.findByRole('dialog')
      fireEvent.click(within(dialog).getByRole('button', { name: 'dialog.confirm' }))
    }
    await waitFor(() => expect(action === 'revoke' ? api.revokeInvitation : api.createInvitation).toHaveBeenCalledTimes(1))
    expect(api.invitationHistory).toHaveBeenCalledTimes(1)
    succeed()
    await waitFor(() => expect(api.invitationHistory).toHaveBeenCalledTimes(2))
  })

  const token = 'a'.repeat(43)
  const storageKey = 'skillhub.namespace.issued-invitation:owner:team'
  const validPreview = { namespaceSlug: 'team', expiresAt: '2999-01-01T00:00:00Z', outcome: 'ALREADY_MEMBER' }

  function mount(client = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
    return render(<QueryClientProvider client={client}><InvitationControls slug="team" /></QueryClientProvider>)
  }

  it('keeps the generated link after dialog reopen and a new QueryClient (tab reload)', async () => {
    api.invitationStatus.mockResolvedValueOnce({ active: false }).mockResolvedValueOnce({ active: false }).mockResolvedValue({ active: true })
    api.createInvitation.mockResolvedValue({ token, replaced: false })
    api.previewInvitation.mockResolvedValue(validPreview)
    const view = mount()
    const button = screen.getByRole('button', { name: 'invitation.create' })
    await waitFor(() => expect((button as HTMLButtonElement).disabled).toBe(false))
    fireEvent.click(button)
    await screen.findByRole('textbox')
    expect(window.sessionStorage.getItem(storageKey)).toBe(token)
    view.unmount()
    mount()
    expect((await screen.findByRole('textbox') as HTMLInputElement).value).toContain(`/join#invite=${token}`)
    expect(api.createInvitation).toHaveBeenCalledTimes(1)
    expect(api.previewInvitation).toHaveBeenCalledWith(token)
    expect(screen.getByRole('button', { name: 'invitation.copy' })).toBeTruthy()
  })

  it.each(['consumed', 'expired', 'revoked'])('clears the remembered link when server reports %s/inactive', async () => {
    window.sessionStorage.setItem(storageKey, token)
    api.invitationStatus.mockResolvedValue({ active: false })
    mount()
    await waitFor(() => expect(window.sessionStorage.getItem(storageKey)).toBeNull())
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(api.previewInvitation).not.toHaveBeenCalled()
  })

  it('clears a replaced token and explains why the active link cannot be recovered', async () => {
    window.sessionStorage.setItem(storageKey, token)
    api.invitationStatus.mockResolvedValue({ active: true })
    api.previewInvitation.mockRejectedValue(new ApiError('invalid', 404))
    mount()
    await screen.findByText('invitation.activeUnavailable')
    expect(window.sessionStorage.getItem(storageKey)).toBeNull()
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(api.createInvitation).not.toHaveBeenCalled()
  })

  it('hides remembered links on validation network failure but retains them for retry', async () => {
    window.sessionStorage.setItem(storageKey, token)
    api.invitationStatus.mockResolvedValue({ active: true })
    api.previewInvitation.mockRejectedValue(new ApiError('network', 0))
    mount()
    await screen.findByRole('alert')
    expect(window.sessionStorage.getItem(storageKey)).toBe(token)
    expect(screen.queryByRole('textbox')).toBeNull()
  })

  it('does not display a token saved by a different authenticated user', async () => {
    window.sessionStorage.setItem(storageKey, token)
    auth.user = { userId: 'another-admin' }
    api.invitationStatus.mockResolvedValue({ active: true })
    mount()
    await screen.findByText('invitation.activeUnavailable')
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(api.previewInvitation).not.toHaveBeenCalled()
  })

  it('clears the token after confirmed revoke', async () => {
    window.sessionStorage.setItem(storageKey, token)
    api.invitationStatus.mockResolvedValue({ active: true })
    api.previewInvitation.mockResolvedValue(validPreview)
    api.revokeInvitation.mockImplementation(async () => { api.invitationStatus.mockResolvedValue({ active: false }) })
    mount()
    await screen.findByRole('textbox')
    fireEvent.click(screen.getByRole('button', { name: 'invitation.revoke' }))
    fireEvent.click(within(await screen.findByRole('dialog')).getByRole('button', { name: 'dialog.confirm' }))
    await waitFor(() => expect(window.sessionStorage.getItem(storageKey)).toBeNull())
    await waitFor(() => expect(screen.queryByRole('textbox')).toBeNull())
  })

  it('keeps only an in-memory copy and explains the limitation when storage is unavailable', async () => {
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('storage unavailable') })
    api.invitationStatus.mockResolvedValueOnce({ active: false }).mockResolvedValueOnce({ active: false }).mockResolvedValue({ active: true })
    api.createInvitation.mockResolvedValue({ token, replaced: false })
    api.previewInvitation.mockResolvedValue(validPreview)
    mount()
    const button = screen.getByRole('button', { name: 'invitation.create' })
    await waitFor(() => expect((button as HTMLButtonElement).disabled).toBe(false))
    fireEvent.click(button)
    await screen.findByRole('textbox')
    expect(screen.getByText('invitation.storageUnavailable')).toBeTruthy()
    expect(window.sessionStorage.getItem(storageKey)).toBeNull()
  })

  it('does not show a previously validated cached link while reopening validation fails', async () => {
    window.sessionStorage.setItem(storageKey, token)
    api.invitationStatus.mockResolvedValue({ active: true })
    api.previewInvitation.mockResolvedValue(validPreview)
    const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
    const view = mount(client)
    await screen.findByRole('textbox')
    view.unmount()
    api.previewInvitation.mockRejectedValue(new ApiError('network', 0))
    mount(client)
    expect(screen.queryByRole('textbox')).toBeNull()
    await screen.findByRole('alert')
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(window.sessionStorage.getItem(storageKey)).toBe(token)
  })

  it('replaces the remembered link only after confirmation and restores the new link on reopen', async () => {
    window.sessionStorage.setItem(storageKey, token)
    const nextToken = 'b'.repeat(43)
    api.invitationStatus.mockResolvedValue({ active: true })
    api.previewInvitation.mockResolvedValue(validPreview)
    api.createInvitation.mockResolvedValue({ token: nextToken, replaced: true })
    const view = mount()
    await screen.findByRole('textbox')
    fireEvent.click(screen.getByRole('button', { name: 'invitation.replaceAction' }))
    const dialog = await screen.findByRole('dialog')
    expect(api.createInvitation).not.toHaveBeenCalled()
    expect(window.sessionStorage.getItem(storageKey)).toBe(token)
    fireEvent.click(within(dialog).getByRole('button', { name: 'dialog.confirm' }))
    await waitFor(() => expect(window.sessionStorage.getItem(storageKey)).toBe(nextToken))
    await waitFor(() => expect((screen.getByRole('textbox') as HTMLInputElement).value).toContain(nextToken))
    expect(api.createInvitation).toHaveBeenCalledWith('team', true)
    view.unmount()
    mount()
    expect((await screen.findByRole('textbox') as HTMLInputElement).value).toContain(nextToken)
    expect(api.createInvitation).toHaveBeenCalledTimes(1)
  })

  it('shows the verified expiration with the link and distinguishes replacement from creation', async () => {
    window.sessionStorage.setItem(storageKey, token)
    api.invitationStatus.mockResolvedValue({ active: true })
    api.previewInvitation.mockResolvedValue(validPreview)
    mount()
    await screen.findByRole('textbox')
    expect(screen.getByText(/invitation.expiresAtLabel/).textContent).toContain('2999')
    expect(screen.getByRole('status').textContent).toBe('invitation.statusActive')
    expect(screen.queryByRole('button', { name: 'invitation.create' })).toBeNull()
    expect(screen.getByRole('button', { name: 'invitation.replaceAction' })).toBeTruthy()
    expect(screen.getByText(/invitation.singleUse/)).toBeTruthy()
    expect(screen.getByText('invitation.accessPolicy')).toBeTruthy()
  })

  it('retries failed validation without creating or replacing an invitation', async () => {
    window.sessionStorage.setItem(storageKey, token)
    api.invitationStatus.mockResolvedValue({ active: true })
    api.previewInvitation.mockRejectedValueOnce(new ApiError('network', 0)).mockResolvedValue(validPreview)
    mount()
    await screen.findByRole('alert')
    expect(screen.getByRole('status').textContent).toBe('invitation.statusError')
    fireEvent.click(screen.getByRole('button', { name: 'invitation.retry' }))
    expect((await screen.findByRole('textbox') as HTMLInputElement).value).toContain(token)
    expect(screen.queryByRole('alert')).toBeNull()
    expect(api.createInvitation).not.toHaveBeenCalled()
    expect(api.revokeInvitation).not.toHaveBeenCalled()
  })

})
