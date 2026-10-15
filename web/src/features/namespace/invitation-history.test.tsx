/** @vitest-environment jsdom */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

const api = vi.hoisted(() => ({ invitationHistory: vi.fn() }))
const auth = vi.hoisted(() => ({ user: { userId: 'owner' } as { userId: string } | null }))
vi.mock('@/api/client', () => ({ namespaceApi: api }))
vi.mock('@/features/auth/use-auth', () => ({ useAuth: () => auth }))
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key, i18n: { language: 'en' } }) }))
import { InvitationHistory } from './invitation-history'

const record = {
  invitationId: 'record-1', issuedBy: 'owner', issuedByName: 'Owner then',
  issuedAt: '2026-10-13T12:00:00Z', expiresAt: '2026-10-14T12:00:00Z',
  status: 'USED', consumedBy: 'joined-user', consumedByName: 'Original joiner',
  consumedAt: '2026-10-13T13:00:00Z', endedAt: '2026-10-13T13:00:00Z', legacy: true,
}
const page = (items = [record], pageIndex = 0, totalPages = 1) => ({ items, page: pageIndex, size: 5, total: items.length, totalPages })
function mount(slug = 'team', client = new QueryClient({ defaultOptions: { queries: { retry: false } } })) {
  return render(<QueryClientProvider client={client}><InvitationHistory key={slug} slug={slug} /></QueryClientProvider>)
}
afterEach(() => { cleanup(); api.invitationHistory.mockReset(); auth.user = { userId: 'owner' } })

describe('InvitationHistory', () => {
  it('shows immutable join facts and legacy status without link or restoration actions', async () => {
    api.invitationHistory.mockResolvedValue(page())
    mount()
    const table = await screen.findByRole('table')
    expect(within(table).getByText('Owner then')).toBeTruthy()
    expect(within(table).queryByText('joined-user')).toBeNull()
    expect(within(table).getByText('Original joiner').getAttribute('title')).toBe('Original joiner (joined-user)')
    expect(within(table).getByText('Original joiner').getAttribute('aria-label')).toBe('Original joiner (joined-user)')
    expect(within(table).getByText('Original joiner')).toBeTruthy()
    expect(within(table).getByText('invitation.historyUsed')).toBeTruthy()
    expect(within(table).getByText('invitation.historyLegacy')).toBeTruthy()
    expect(screen.queryByRole('textbox')).toBeNull()
    expect(screen.queryByRole('link')).toBeNull()
    expect(api.invitationHistory).toHaveBeenCalledWith('team', 0, 5)
  })
  it('requests real next page, displays loading without previous page rows, and resets for another namespace', async () => {
    api.invitationHistory.mockResolvedValueOnce(page([record], 0, 2))
    mount()
    await screen.findByRole('table')
    let finish: (value: ReturnType<typeof page>) => void = () => {}
    api.invitationHistory.mockImplementationOnce(() => new Promise((resolve) => { finish = resolve }))
    fireEvent.click(screen.getByRole('button', { name: 'pagination.next' }))
    await waitFor(() => expect(api.invitationHistory).toHaveBeenLastCalledWith('team', 1, 5))
    expect(screen.queryByText('Original joiner')).toBeNull()
    expect(screen.getByRole('status').textContent).toBe('invitation.historyLoading')
    finish(page([{ ...record, invitationId: 'record-2', consumedByName: 'Second joiner' }], 1, 2))
    await screen.findAllByText('Second joiner')
    cleanup()
    api.invitationHistory.mockResolvedValue(page([], 0))
    mount('another')
    await screen.findByText('invitation.historyEmpty')
    expect(api.invitationHistory).toHaveBeenLastCalledWith('another', 0, 5)
    expect(screen.queryByText('Second joiner')).toBeNull()
  })
  it('uses snapshot names with full identifiers available without a second visible line', async () => {
    const id = 'usr_6ac9b4a8-4d82-4233-a544-6a1f6b5f6208'
    const name = 'An exceptionally long historical display name that stays on one line'
    api.invitationHistory.mockResolvedValue(page([{ ...record, consumedBy: id, consumedByName: name }]))
    mount()
    const table = await screen.findByRole('table')
    const identity = within(table).getByText(name)
    expect(identity.getAttribute('title')).toBe(`${name} (${id})`)
    expect(identity.getAttribute('aria-label')).toBe(`${name} (${id})`)
    expect(identity.classList.contains('truncate')).toBe(true)
    expect(within(table).queryByText(id)).toBeNull()
    expect(identity.children.length).toBe(0)
  })
  it('abbreviates a missing-name legacy identifier while retaining the full identifier', async () => {
    const id = 'usr_6ac9b4a8-4d82-4233-a544-6a1f6b5f6208'
    api.invitationHistory.mockResolvedValue(page([{ ...record, consumedBy: id, consumedByName: '' }]))
    mount()
    const table = await screen.findByRole('table')
    const identity = within(table).getByText('usr_6ac9…5f6208')
    expect(identity.getAttribute('title')).toBe(id)
    expect(identity.getAttribute('aria-label')).toBe(id)
    expect(within(table).queryByText(id)).toBeNull()
  })
  it('keeps a short missing-name identifier readable in full', async () => {
    api.invitationHistory.mockResolvedValue(page([{ ...record, consumedByName: '' }]))
    mount()
    const table = await screen.findByRole('table')
    expect(within(table).getByText('joined-user').getAttribute('title')).toBe('joined-user')
  })
  it('hides prior records when refresh fails including 403, then allows retry', async () => {
    api.invitationHistory.mockResolvedValueOnce(page()).mockRejectedValueOnce(new Error('403 forbidden')).mockResolvedValue(page([]))
    mount()
    await screen.findByRole('table')
    fireEvent.click(screen.getByRole('button', { name: 'invitation.historyRefresh' }))
    await screen.findByRole('alert')
    expect(screen.queryByRole('table')).toBeNull()
    expect(screen.queryByText('Original joiner')).toBeNull()
    fireEvent.click(screen.getByRole('button', { name: 'invitation.historyRefresh' }))
    await screen.findByText('invitation.historyEmpty')
    expect(screen.queryByRole('alert')).toBeNull()
  })
  it('does not request history without a logged in identity', () => {
    auth.user = null
    mount()
    expect(api.invitationHistory).not.toHaveBeenCalled()
  })
})
