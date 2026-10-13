/** @vitest-environment jsdom */
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ stage: vi.fn() }))
vi.mock('@/api/client', () => ({ skillAvatarApi: api, buildApiUrl: (url: string) => url }))
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }))
import { SkillAvatarEditor } from './skill-avatar-editor'

function selectImage() {
  fireEvent.change(screen.getByLabelText('skillAvatar.choose'), { target: { files: [new File(['image'], 'avatar.png', { type: 'image/png' })] } })
}
function deferred<T>() {
  let resolve!: (value: T) => void
  const promise = new Promise<T>(done => { resolve = done })
  return { promise, resolve }
}
const uploaded = { uploadId: 'private-upload-1', previewUrl: '/private/preview', expiresAt: '2099-01-01T00:00:00Z' }

describe('SkillAvatarEditor staged uploads', () => {
  beforeEach(() => {
    api.stage.mockReset()
    vi.stubGlobal('Image', class { src = ''; naturalWidth = 1024; naturalHeight = 768; decode() { return Promise.resolve() } })
    vi.stubGlobal('URL', { createObjectURL: vi.fn(() => 'blob:chosen'), revokeObjectURL: vi.fn() })
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ drawImage: vi.fn() } as unknown as CanvasRenderingContext2D)
    vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation(callback => callback(new Blob(['cropped'], { type: 'image/png' })))
  })
  afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

  it('requires upload before submit and stages the cropped file without associating a skill', async () => {
    const change = vi.fn()
    const task = deferred<typeof uploaded>()
    api.stage.mockReturnValue(task.promise)
    render(<SkillAvatarEditor name="Foo" currentUrl="/active" onChange={change} />)
    selectImage()
    expect(change).toHaveBeenLastCalledWith({ action: 'REPLACE', ready: false })
    fireEvent.click(screen.getByRole('button', { name: 'skillAvatar.cropUpload' }))
    await waitFor(() => expect(api.stage).toHaveBeenCalledOnce())
    expect(api.stage.mock.calls[0][0]).toBeInstanceOf(Blob)
    expect(screen.getByRole('status').textContent).toBe('skillAvatar.uploadStates.UPLOADING')
    task.resolve(uploaded)
    await waitFor(() => expect(change).toHaveBeenLastCalledWith({ action: 'REPLACE', uploadId: uploaded.uploadId, ready: true }))
    expect(screen.getByAltText('skillAvatar.cropPreview').getAttribute('src')).toBe(uploaded.previewUrl)
  })

  it('blocks invalid selections until canceled without touching the active avatar', () => {
    const change = vi.fn()
    render(<SkillAvatarEditor name="Foo" currentUrl="/active" onChange={change} />)
    fireEvent.change(screen.getByLabelText('skillAvatar.choose'), { target: { files: [new File(['bad'], 'bad.svg', { type: 'image/svg+xml' })] } })
    expect(screen.getByRole('alert').textContent).toBe('skillAvatar.invalid')
    expect(change).toHaveBeenLastCalledWith({ action: 'REPLACE', ready: false })
    expect(api.stage).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'skillAvatar.discardChange' }))
    expect(change).toHaveBeenLastCalledWith({ action: 'KEEP', ready: true })
    expect(screen.getByRole('status').textContent).toBe('skillAvatar.uploadStates.KEEP')
  })

  it('keeps submission blocked after upload failure and allows a successful retry', async () => {
    const change = vi.fn()
    api.stage.mockRejectedValueOnce(new Error('storage unavailable')).mockResolvedValueOnce(uploaded)
    render(<SkillAvatarEditor name="Foo" onChange={change} />)
    selectImage()
    fireEvent.click(screen.getByRole('button', { name: 'skillAvatar.cropUpload' }))
    expect((await screen.findByRole('alert')).textContent).toBe('storage unavailable')
    expect(change).toHaveBeenLastCalledWith({ action: 'REPLACE', ready: false })
    fireEvent.click(screen.getByRole('button', { name: 'skillAvatar.uploadRetry' }))
    await waitFor(() => expect(change).toHaveBeenLastCalledWith({ action: 'REPLACE', uploadId: uploaded.uploadId, ready: true }))
  })

  it.each(['discardChange', 'remove'] as const)('ignores an old upload completion after %s', async action => {
    const change = vi.fn()
    const task = deferred<typeof uploaded>()
    api.stage.mockReturnValue(task.promise)
    render(<SkillAvatarEditor name="Foo" currentUrl="/active" canRemove onChange={change} />)
    selectImage()
    fireEvent.click(screen.getByRole('button', { name: 'skillAvatar.cropUpload' }))
    await waitFor(() => expect(api.stage).toHaveBeenCalledOnce())
    fireEvent.click(screen.getByRole('button', { name: `skillAvatar.${action}` }))
    task.resolve(uploaded)
    await waitFor(() => expect(change).toHaveBeenLastCalledWith({ action: action === 'remove' ? 'REMOVE' : 'KEEP', ready: true }))
    expect(change.mock.calls.some(([value]) => value.uploadId)).toBe(false)
    expect(screen.queryByAltText('skillAvatar.cropPreview')).toBeNull()
  })

  it('invalidates a staged reference when a different image is selected', async () => {
    const change = vi.fn()
    api.stage.mockResolvedValue(uploaded)
    render(<SkillAvatarEditor name="Foo" onChange={change} />)
    selectImage()
    fireEvent.click(screen.getByRole('button', { name: 'skillAvatar.cropUpload' }))
    await waitFor(() => expect(change).toHaveBeenLastCalledWith({ action: 'REPLACE', uploadId: uploaded.uploadId, ready: true }))
    selectImage()
    expect(change).toHaveBeenLastCalledWith({ action: 'REPLACE', ready: false })
    expect(screen.getByRole('status').textContent).toBe('skillAvatar.uploadStates.SELECTED')
  })

  it('previews removal as a draft and cancel restores the original image', () => {
    const change = vi.fn()
    render(<SkillAvatarEditor name="Foo" currentUrl="/active" canRemove onChange={change} />)
    fireEvent.click(screen.getByRole('button', { name: 'skillAvatar.remove' }))
    expect(change).toHaveBeenLastCalledWith({ action: 'REMOVE', ready: true })
    expect(screen.getByText('F')).toBeTruthy()
    expect(api.stage).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'skillAvatar.discardChange' }))
    expect(document.querySelector('img')?.getAttribute('src')).toBe('/active')
  })
})
