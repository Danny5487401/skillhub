/** @vitest-environment jsdom */
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { ReactNode } from 'react'

const api = vi.hoisted(() => ({ getVisibility: vi.fn(), changeVisibility: vi.fn() }))
vi.mock('@/api/client', () => ({ skillLifecycleApi: api }))
vi.mock('@/shared/lib/toast', () => ({ toast: { success: vi.fn() } }))
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }))
vi.mock('@/shared/ui/select', () => ({
  Select: ({ value, onValueChange, children, disabled }: { value: string; onValueChange: (value: string) => void; children: ReactNode; disabled?: boolean }) =>
    <select value={value} onChange={(event) => onValueChange(event.target.value)} disabled={disabled}>{children}</select>,
  SelectTrigger: () => null,
  SelectValue: () => null,
  SelectContent: ({ children }: { children: ReactNode }) => <>{children}</>,
  SelectItem: ({ value, children }: { value: string; children: ReactNode }) => <option value={value}>{children}</option>,
}))
import { SkillVisibilityDialog } from './skill-visibility-dialog'

function mount(visibility = 'PUBLIC') {
  const client = new QueryClient({ defaultOptions: { queries: { retry: false } } })
  render(<QueryClientProvider client={client}><SkillVisibilityDialog namespace="team" slug="foo" visibility={visibility} /></QueryClientProvider>)
  fireEvent.click(screen.getByRole('button', { name: 'skillVisibility.title' }))
}

describe('SkillVisibilityDialog', () => {
  afterEach(() => { cleanup(); vi.clearAllMocks() })

  it('changes only visibility without uploading a new package', async () => {
    api.getVisibility.mockResolvedValue({ visibility: 'PUBLIC' })
    api.changeVisibility.mockResolvedValue({ visibility: 'PRIVATE' })
    mount()
    await waitFor(() => expect(api.getVisibility).toHaveBeenCalledWith('team', 'foo'))
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'PRIVATE' } })
    await waitFor(() => expect(screen.getByRole('button', { name: 'common.save' }).hasAttribute('disabled')).toBe(false))
    fireEvent.click(screen.getByRole('button', { name: 'common.save' }))
    await waitFor(() => expect(api.changeVisibility).toHaveBeenCalledWith('team', 'foo', 'PRIVATE'))
  })

  it('explains that wider access requires review', async () => {
    api.getVisibility.mockResolvedValue({ visibility: 'PRIVATE' })
    mount('PRIVATE')
    await waitFor(() => expect(api.getVisibility).toHaveBeenCalled())
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'PUBLIC' } })
    expect(screen.getByText('skillVisibility.expanding')).toBeTruthy()
    expect(screen.getByRole('button', { name: 'skillVisibility.submit' })).toBeTruthy()
  })

  it('reloads a pending request and prevents duplicate submission', async () => {
    api.getVisibility.mockResolvedValue({ visibility: 'PRIVATE', requestedVisibility: 'PUBLIC', reviewTaskId: 42 })
    mount('PRIVATE')
    await screen.findByRole('status')
    expect(screen.getByRole('combobox').hasAttribute('disabled')).toBe(true)
    expect(screen.getByRole('button', { name: 'common.save' }).hasAttribute('disabled')).toBe(true)
    expect(api.changeVisibility).not.toHaveBeenCalled()
  })

  it('does not enable saving when current state cannot be read', async () => {
    api.getVisibility.mockRejectedValue(new Error('unavailable'))
    mount()
    await screen.findByRole('alert')
    fireEvent.change(screen.getByRole('combobox'), { target: { value: 'PRIVATE' } })
    expect(screen.getByRole('button', { name: 'common.save' }).hasAttribute('disabled')).toBe(true)
  })
})
