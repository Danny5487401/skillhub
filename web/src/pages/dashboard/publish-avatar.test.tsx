/** @vitest-environment jsdom */
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
const mocks = vi.hoisted(() => ({ publish: vi.fn(), navigate: vi.fn(), search: { namespace: 'team', visibility: 'PUBLIC', updateSkill: 'foo' } }))
vi.mock('@tanstack/react-query', () => ({ useQuery: () => ({ data: { avatarUrl: '/owner-active' }, isLoading: false, isError: false }) }))
vi.mock('@tanstack/react-router', () => ({ useNavigate: () => mocks.navigate, useSearch: () => mocks.search }))
vi.mock('react-i18next', async () => ({ ...await vi.importActual<typeof import('react-i18next')>('react-i18next'), useTranslation: () => ({ t: (key: string) => key }) }))
vi.mock('@/shared/hooks/use-skill-queries', () => ({
  usePublishSkill: () => ({ mutateAsync: mocks.publish, isPending: false }),
  useSkillDetail: () => ({ data: { displayName: 'Foo', avatarUrl: '/active' }, isLoading: false, isError: false }),
}))
vi.mock('@/shared/hooks/use-namespace-queries', () => ({ useMyNamespaces: () => ({ data: [{ slug: 'team', displayName: 'Team' }], isLoading: false }) }))
vi.mock('@/features/publish/upload-zone', () => ({ UploadZone: ({ onFileSelect }: { onFileSelect: (file: File) => void }) => <button onClick={() => onFileSelect(new File(['zip'], 'skill.zip'))}>choose package</button> }))
vi.mock('@/features/skill/skill-avatar-editor', () => ({
  KEEP_AVATAR: { action: 'KEEP', ready: true },
  SkillAvatarEditor: ({ onChange }: { onChange: (value: { action: 'KEEP' | 'REPLACE' | 'REMOVE'; ready: boolean; uploadId?: string }) => void }) => <>
    <button onClick={() => onChange({ action: 'REPLACE', ready: false })}>image pending</button>
    <button onClick={() => onChange({ action: 'REPLACE', ready: true, uploadId: 'private-reference' })}>image uploaded</button>
    <button onClick={() => onChange({ action: 'REMOVE', ready: true })}>remove draft</button>
  </>,
}))
vi.mock('@/shared/lib/navigate-after-overlays', () => ({ navigateAfterOverlays: (fn: () => void) => fn() }))
vi.mock('@/shared/lib/toast', () => ({ toast: { success: vi.fn(), error: vi.fn() } }))
import { PublishPage } from './publish'

describe('Publish avatar submission', () => {
  beforeEach(() => { mocks.publish.mockReset(); mocks.navigate.mockReset(); mocks.search.updateSkill = 'foo' })
  afterEach(cleanup)

  it('blocks publishing a selected image until staged upload completes', async () => {
    mocks.publish.mockResolvedValue({ namespace: 'team', slug: 'foo', version: '1.1', status: 'PENDING_REVIEW' })
    render(<PublishPage />)
    fireEvent.click(screen.getByText('choose package'))
    fireEvent.click(screen.getByText('image pending'))
    expect(screen.getByRole('button', { name: 'publish.confirm' }).hasAttribute('disabled')).toBe(true)
    expect(mocks.publish).not.toHaveBeenCalled()
    fireEvent.click(screen.getByText('image uploaded'))
    fireEvent.click(screen.getByRole('button', { name: 'publish.confirm' }))
    await waitFor(() => expect(mocks.publish).toHaveBeenCalledWith(expect.objectContaining({ avatarAction: 'REPLACE', avatarUploadId: 'private-reference', expectedSlug: 'foo' })))
  })

  it('preserves the uploaded reference after a failed skill submission so retry does not reupload', async () => {
    mocks.publish.mockRejectedValueOnce(new Error('package invalid')).mockResolvedValueOnce({ namespace: 'team', slug: 'foo', version: '1.1', status: 'PUBLISHED' })
    render(<PublishPage />)
    fireEvent.click(screen.getByText('choose package'))
    fireEvent.click(screen.getByText('image uploaded'))
    fireEvent.click(screen.getByRole('button', { name: 'publish.confirm' }))
    await waitFor(() => expect(mocks.publish).toHaveBeenCalledTimes(1))
    expect(mocks.navigate).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'publish.confirm' }))
    await waitFor(() => expect(mocks.publish).toHaveBeenCalledTimes(2))
    expect(mocks.publish.mock.calls[1][0]).toEqual(mocks.publish.mock.calls[0][0])
  })

  it('sends removal only with the successful update request, not while editing', async () => {
    mocks.publish.mockResolvedValue({ namespace: 'team', slug: 'foo', version: '1.1', status: 'PENDING_REVIEW' })
    render(<PublishPage />)
    fireEvent.click(screen.getByText('remove draft'))
    expect(mocks.publish).not.toHaveBeenCalled()
    fireEvent.click(screen.getByText('choose package'))
    fireEvent.click(screen.getByRole('button', { name: 'publish.confirm' }))
    await waitFor(() => expect(mocks.publish).toHaveBeenCalledWith(expect.objectContaining({ avatarAction: 'REMOVE', expectedSlug: 'foo' })))
  })

  it('keeps the existing avatar by default when updating only skill content', async () => {
    mocks.publish.mockResolvedValue({ namespace: 'team', slug: 'foo', version: '1.1', status: 'PENDING_REVIEW' })
    render(<PublishPage />)
    fireEvent.click(screen.getByText('choose package'))
    fireEvent.click(screen.getByRole('button', { name: 'publish.confirm' }))
    await waitFor(() => expect(mocks.publish).toHaveBeenCalledWith(expect.objectContaining({ avatarAction: 'KEEP', avatarUploadId: undefined, expectedSlug: 'foo' })))
  })
  it('clears the avatar draft when navigating to another skill in the same namespace', async () => {
    mocks.publish.mockResolvedValue({ namespace: 'team', slug: 'bar', version: '1.1', status: 'PENDING_REVIEW' })
    const view = render(<PublishPage />)
    fireEvent.click(screen.getByText('choose package'))
    fireEvent.click(screen.getByText('remove draft'))
    mocks.search.updateSkill = 'bar'
    view.rerender(<PublishPage />)
    fireEvent.click(screen.getByRole('button', { name: 'publish.confirm' }))
    await waitFor(() => expect(mocks.publish).toHaveBeenCalledWith(expect.objectContaining({ avatarAction: 'KEEP', avatarUploadId: undefined, expectedSlug: 'bar' })))
  })

})
