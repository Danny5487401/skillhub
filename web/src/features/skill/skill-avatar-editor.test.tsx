/** @vitest-environment jsdom */
import { cleanup, fireEvent, render, screen, waitFor, within } from '@testing-library/react'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
const api = vi.hoisted(() => ({ stage: vi.fn() }))
const translation = vi.hoisted(() => ({ t: (key: string) => key }))
vi.mock('@/api/client', () => ({ skillAvatarApi: api, buildApiUrl: (url: string) => url }))
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: translation.t }) }))
import { SkillAvatarEditor } from './skill-avatar-editor'
import en from '@/i18n/locales/en.json'
import zh from '@/i18n/locales/zh.json'
import ru from '@/i18n/locales/ru.json'

async function selectImage() {
  fireEvent.change(screen.getByLabelText('skillAvatar.choose'), { target: { files: [new File(['image'], 'avatar.png', { type: 'image/png' })] } })
  const dialog = await screen.findByRole('dialog')
  const image = within(dialog).getByAltText('skillAvatar.cropPreview')
  fireEvent.load(image)
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
    translation.t = (key: string) => key
    vi.stubGlobal('PointerEvent', MouseEvent)
    vi.spyOn(HTMLImageElement.prototype, 'naturalWidth', 'get').mockReturnValue(1024)
    vi.spyOn(HTMLImageElement.prototype, 'naturalHeight', 'get').mockReturnValue(768)
    vi.spyOn(HTMLImageElement.prototype, 'width', 'get').mockReturnValue(256)
    vi.spyOn(HTMLImageElement.prototype, 'height', 'get').mockReturnValue(192)
    vi.spyOn(HTMLElement.prototype, 'getBoundingClientRect').mockReturnValue({ x: 0, y: 0, width: 256, height: 192, top: 0, left: 0, right: 256, bottom: 192, toJSON: () => ({}) })
    vi.stubGlobal('URL', { createObjectURL: vi.fn(() => 'blob:chosen'), revokeObjectURL: vi.fn() })
    vi.spyOn(HTMLCanvasElement.prototype, 'getContext').mockReturnValue({ drawImage: vi.fn(), scale: vi.fn(), save: vi.fn(), translate: vi.fn(), rotate: vi.fn(), restore: vi.fn() } as unknown as CanvasRenderingContext2D)
    vi.spyOn(HTMLCanvasElement.prototype, 'toBlob').mockImplementation(callback => callback(new Blob(['cropped'], { type: 'image/png' })))
  })
  afterEach(() => { cleanup(); vi.restoreAllMocks(); vi.unstubAllGlobals() })

  it.each([{ locale: 'en', messages: en }, { locale: 'zh', messages: zh }, { locale: 'ru', messages: ru }])('renders the cancel action from the actual $locale messages', async ({ messages }) => {
    translation.t = key => {
      let value: unknown = messages
      for (const part of key.split('.')) value = value && typeof value === 'object' ? (value as Record<string, unknown>)[part] : undefined
      return typeof value === 'string' ? value : key
    }
    const change = vi.fn()
    render(<SkillAvatarEditor name="Foo" onChange={change} />)
    fireEvent.change(screen.getByLabelText(messages.skillAvatar.choose), { target: { files: [new File(['image'], 'avatar.png', { type: 'image/png' })] } })
    const dialog = await screen.findByRole('dialog', { name: messages.skillAvatar.cropTitle })
    fireEvent.click(within(dialog).getByRole('button', { name: messages.dialog.cancel }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    expect(change).toHaveBeenLastCalledWith({ action: 'KEEP', ready: true })
  })

  it('exports the moved library selection rather than always cropping the image center', async () => {
    api.stage.mockResolvedValue(uploaded)
    render(<SkillAvatarEditor name="Foo" onChange={vi.fn()} />)
    await selectImage()
    const area = screen.getByRole('group', { name: 'skillAvatar.cropArea' })
    const initialLeft = area.style.left
    fireEvent.keyDown(area, { key: 'ArrowRight', shiftKey: true })
    expect(area.style.left).not.toBe(initialLeft)
    fireEvent.click(screen.getByRole('button', { name: 'skillAvatar.cropConfirm' }))
    await waitFor(() => expect(api.stage).toHaveBeenCalledOnce())
    const context = vi.mocked(HTMLCanvasElement.prototype.getContext).mock.results[0].value as unknown as { translate: ReturnType<typeof vi.fn>; drawImage: ReturnType<typeof vi.fn> }
    // Initial square is 576px at x=224. Library Shift+Right moves 10 display px = 40 source px.
    expect(context.translate).toHaveBeenCalledWith(-264, -96)
    expect(context.drawImage.mock.calls.some(args => args.length === 5 && args.slice(1).join(',') === '0,0,512,512')).toBe(true)
    expect(api.stage.mock.calls[0][0].type).toBe('image/png')
  })

  it('exports a dragged selection with the library pointer handlers', async () => {
    api.stage.mockResolvedValue(uploaded)
    render(<SkillAvatarEditor name="Foo" onChange={vi.fn()} />)
    await selectImage()
    const area = screen.getByRole('group', { name: 'skillAvatar.cropArea' })
    fireEvent.pointerDown(area, { clientX: 128, clientY: 96 })
    fireEvent.pointerMove(document, { clientX: 148, clientY: 106 })
    fireEvent.pointerUp(document)
    fireEvent.click(screen.getByRole('button', { name: 'skillAvatar.cropConfirm' }))
    await waitFor(() => expect(api.stage).toHaveBeenCalledOnce())
    const context = vi.mocked(HTMLCanvasElement.prototype.getContext).mock.results[0].value as unknown as { translate: ReturnType<typeof vi.fn> }
    expect(context.translate.mock.calls[0][0]).toBeCloseTo(-304)
    expect(context.translate.mock.calls[0][1]).toBeCloseTo(-136)
  })

  it('does not upload if canceled while the PNG conversion is still in progress', async () => {
    const change = vi.fn()
    let finish!: BlobCallback
    vi.mocked(HTMLCanvasElement.prototype.toBlob).mockImplementation(callback => { finish = callback })
    render(<SkillAvatarEditor name="Foo" onChange={change} />)
    await selectImage()
    fireEvent.click(screen.getByRole('button', { name: 'skillAvatar.cropConfirm' }))
    await waitFor(() => expect(finish).toBeTypeOf('function'))
    fireEvent.click(screen.getByRole('button', { name: 'dialog.cancel' }))
    finish(new Blob(['cropped'], { type: 'image/png' }))
    await waitFor(() => expect(change).toHaveBeenLastCalledWith({ action: 'KEEP', ready: true }))
    expect(api.stage).not.toHaveBeenCalled()
  })

  it('keeps the file chooser keyboard accessible and disables it during an upload', async () => {
    api.stage.mockReturnValue(new Promise(() => {}))
    render(<SkillAvatarEditor name="Foo" onChange={vi.fn()} />)
    const input = screen.getByLabelText('skillAvatar.choose') as HTMLInputElement
    expect(input.tabIndex).toBe(-1)
    const choose = screen.getByRole('button', { name: 'skillAvatar.choose' }) as HTMLButtonElement
    expect(choose.disabled).toBe(false)
    expect(input.className).toContain('sr-only')
    expect(input.disabled).toBe(false)
    await selectImage()
    fireEvent.click(screen.getByRole('button', { name: 'skillAvatar.cropConfirm' }))
    expect(input.disabled).toBe(true)
  })

  it('rejects images above 2MB before attempting an upload', async () => {
    const change = vi.fn()
    render(<SkillAvatarEditor name="Foo" onChange={change} />)
    fireEvent.change(screen.getByLabelText('skillAvatar.choose'), { target: { files: [new File([new Uint8Array(2 * 1024 * 1024 + 1)], 'avatar.png', { type: 'image/png' })] } })
    expect((await screen.findByRole('alert')).textContent).toBe('skillAvatar.invalid')
    expect(change).toHaveBeenLastCalledWith({ action: 'REPLACE', ready: false })
    expect(api.stage).not.toHaveBeenCalled()
  })

  it('requires the selected image to load before cropping and reports decode errors', async () => {
    render(<SkillAvatarEditor name="Foo" onChange={vi.fn()} />)
    fireEvent.change(screen.getByLabelText('skillAvatar.choose'), { target: { files: [new File(['image'], 'avatar.png', { type: 'image/png' })] } })
    const image = await screen.findByAltText('skillAvatar.cropPreview')
    expect((screen.getByRole('button', { name: 'skillAvatar.cropConfirm' }) as HTMLButtonElement).disabled).toBe(true)
    fireEvent.error(image)
    expect(screen.getByRole('alert').textContent).toBe('skillAvatar.invalid')
    expect(api.stage).not.toHaveBeenCalled()
  })

  it('requires upload before submit and stages the cropped file without associating a skill', async () => {
    const change = vi.fn()
    const task = deferred<typeof uploaded>()
    api.stage.mockReturnValue(task.promise)
    render(<SkillAvatarEditor name="Foo" currentUrl="/active" onChange={change} />)
    await selectImage()
    expect(change).toHaveBeenLastCalledWith({ action: 'REPLACE', ready: false })
    fireEvent.click(screen.getByRole('button', { name: 'skillAvatar.cropConfirm' }))
    await waitFor(() => expect(api.stage).toHaveBeenCalledOnce())
    expect(api.stage.mock.calls[0][0]).toBeInstanceOf(Blob)
    expect(screen.getByRole('status').textContent).toBe('skillAvatar.uploadStates.UPLOADING')
    task.resolve(uploaded)
    await waitFor(() => expect(change).toHaveBeenLastCalledWith({ action: 'REPLACE', uploadId: uploaded.uploadId, ready: true }))
    expect(screen.getByAltText('skillAvatar.cropPreview').getAttribute('src')).toBe(uploaded.previewUrl)
  })

  it.each([['bad.svg', 'image/svg+xml'], ['bad.png', 'image/gif']])('blocks invalid selection %s until canceled without touching the active avatar', async (filename, mime) => {
    const change = vi.fn()
    render(<SkillAvatarEditor name="Foo" currentUrl="/active" onChange={change} />)
    fireEvent.change(screen.getByLabelText('skillAvatar.choose'), { target: { files: [new File(['bad'], filename, { type: mime })] } })
    expect((await screen.findByRole('alert')).textContent).toBe('skillAvatar.invalid')
    expect(change).toHaveBeenLastCalledWith({ action: 'REPLACE', ready: false })
    expect(api.stage).not.toHaveBeenCalled()
    fireEvent.click(screen.getByRole('button', { name: 'skillAvatar.discardChange' }))
    expect(change).toHaveBeenLastCalledWith({ action: 'KEEP', ready: true })
    expect(screen.queryByRole('status')).toBeNull()
    expect(document.querySelector('img')?.getAttribute('src')).toBe('/active')
  })

  it('keeps submission blocked after upload failure and allows a successful retry', async () => {
    const change = vi.fn()
    api.stage.mockRejectedValueOnce(new Error('storage unavailable')).mockResolvedValueOnce(uploaded)
    render(<SkillAvatarEditor name="Foo" onChange={change} />)
    await selectImage()
    fireEvent.click(screen.getByRole('button', { name: 'skillAvatar.cropConfirm' }))
    expect((await screen.findByRole('alert')).textContent).toBe('storage unavailable')
    expect(change).toHaveBeenLastCalledWith({ action: 'REPLACE', ready: false })
    fireEvent.click(screen.getByRole('button', { name: 'skillAvatar.uploadRetry' }))
    await waitFor(() => expect(change).toHaveBeenLastCalledWith({ action: 'REPLACE', uploadId: uploaded.uploadId, ready: true }))
  })

  it.each(['cancel', 'escape'] as const)('ignores an old upload completion after %s', async action => {
    const change = vi.fn()
    const task = deferred<typeof uploaded>()
    api.stage.mockReturnValue(task.promise)
    render(<SkillAvatarEditor name="Foo" currentUrl="/active" canRemove onChange={change} />)
    await selectImage()
    fireEvent.click(screen.getByRole('button', { name: 'skillAvatar.cropConfirm' }))
    await waitFor(() => expect(api.stage).toHaveBeenCalledOnce())
    if (action === 'cancel') fireEvent.click(screen.getByRole('button', { name: 'dialog.cancel' }))
    else fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    task.resolve(uploaded)
    await waitFor(() => expect(change).toHaveBeenLastCalledWith({ action: 'KEEP', ready: true }))
    expect(change.mock.calls.some(([value]) => value.uploadId)).toBe(false)
    expect(screen.queryByAltText('skillAvatar.cropPreview')).toBeNull()
    expect(screen.queryByRole('dialog')).toBeNull()
  })

  it('invalidates a staged reference when a different image is selected', async () => {
    const change = vi.fn()
    api.stage.mockResolvedValue(uploaded)
    render(<SkillAvatarEditor name="Foo" onChange={change} />)
    await selectImage()
    fireEvent.click(screen.getByRole('button', { name: 'skillAvatar.cropConfirm' }))
    await waitFor(() => expect(change).toHaveBeenLastCalledWith({ action: 'REPLACE', uploadId: uploaded.uploadId, ready: true }))
    await selectImage()
    expect(change).toHaveBeenLastCalledWith({ action: 'REPLACE', ready: false })
    expect(screen.getByRole('dialog')).toBeTruthy()
    expect(screen.queryByRole('status')).toBeNull()
  })


  it('uses the shared portal dialog and restores chooser focus when Escape cancels', async () => {
    const { container } = render(<SkillAvatarEditor name="Foo" onChange={vi.fn()} />)
    const chooser = screen.getByRole('button', { name: 'skillAvatar.choose' })
    chooser.focus()
    await selectImage()
    const dialog = screen.getByRole('dialog', { name: 'skillAvatar.cropTitle' })
    expect(container.contains(dialog)).toBe(false)
    expect(within(dialog).getByText('skillAvatar.cropHelp')).toBeTruthy()
    expect(container.querySelector('.ReactCrop')).toBeNull()
    fireEvent.keyDown(dialog, { key: 'Escape' })
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await waitFor(() => expect(document.activeElement).toBe(chooser))
    expect(api.stage).not.toHaveBeenCalled()
    expect(URL.revokeObjectURL).toHaveBeenCalledWith('blob:chosen')
  })

  it('restores an already uploaded replacement when a second crop is canceled', async () => {
    const change = vi.fn()
    api.stage.mockResolvedValue(uploaded)
    render(<SkillAvatarEditor name="Foo" onChange={change} />)
    await selectImage()
    fireEvent.click(screen.getByRole('button', { name: 'skillAvatar.cropConfirm' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await selectImage()
    fireEvent.click(screen.getByRole('button', { name: 'dialog.cancel' }))
    expect(change).toHaveBeenLastCalledWith({ action: 'REPLACE', uploadId: uploaded.uploadId, ready: true })
    expect(screen.getByAltText('skillAvatar.cropPreview').getAttribute('src')).toBe(uploaded.previewUrl)
    expect(api.stage).toHaveBeenCalledOnce()
  })

  it('does not overwrite a prior staged draft with a late second upload', async () => {
    const change = vi.fn()
    const late = deferred<typeof uploaded>()
    api.stage.mockResolvedValueOnce(uploaded).mockReturnValueOnce(late.promise)
    render(<SkillAvatarEditor name="Foo" onChange={change} />)
    await selectImage()
    fireEvent.click(screen.getByRole('button', { name: 'skillAvatar.cropConfirm' }))
    await waitFor(() => expect(screen.queryByRole('dialog')).toBeNull())
    await selectImage()
    fireEvent.click(screen.getByRole('button', { name: 'skillAvatar.cropConfirm' }))
    await waitFor(() => expect(api.stage).toHaveBeenCalledTimes(2))
    fireEvent.keyDown(screen.getByRole('dialog'), { key: 'Escape' })
    late.resolve({ ...uploaded, uploadId: 'stale-second-upload', previewUrl: '/private/second' })
    await waitFor(() => expect(change).toHaveBeenLastCalledWith({ action: 'REPLACE', uploadId: uploaded.uploadId, ready: true }))
    expect(change.mock.calls.some(([value]) => value.uploadId === 'stale-second-upload')).toBe(false)
    expect(screen.getByAltText('skillAvatar.cropPreview').getAttribute('src')).toBe(uploaded.previewUrl)
  })

  it('restores a removal draft when the crop dialog close button is used', async () => {
    const change = vi.fn()
    render(<SkillAvatarEditor name="Foo" currentUrl="/active" canRemove onChange={change} />)
    fireEvent.click(screen.getByRole('button', { name: 'skillAvatar.remove' }))
    await selectImage()
    fireEvent.click(screen.getByRole('button', { name: 'Close' }))
    expect(change).toHaveBeenLastCalledWith({ action: 'REMOVE', ready: true })
    expect(screen.queryByRole('dialog')).toBeNull()
    expect(screen.getByText('F')).toBeTruthy()
    expect(api.stage).not.toHaveBeenCalled()
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
