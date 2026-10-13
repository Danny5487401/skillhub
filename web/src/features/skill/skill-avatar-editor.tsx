import { useEffect, useRef, useState } from 'react'
import ReactCrop, { centerCrop, makeAspectCrop, convertToPixelCrop, cropToCanvas, type PercentCrop } from 'react-image-crop'
import { useDropzone } from 'react-dropzone'
import 'react-image-crop/dist/ReactCrop.css'
import { useTranslation } from 'react-i18next'
import { buildApiUrl, skillAvatarApi } from '@/api/client'
import { Button } from '@/shared/ui/button'
import { Input } from '@/shared/ui/input'
import { SkillAvatar } from './skill-avatar'

export type AvatarEdit = { action: 'KEEP' | 'REPLACE' | 'REMOVE'; uploadId?: string; ready: boolean }
export const KEEP_AVATAR: AvatarEdit = { action: 'KEEP', ready: true }

async function exportCrop(image: HTMLImageElement, crop: PercentCrop): Promise<Blob> {
  const cropped = document.createElement('canvas')
  await cropToCanvas(image, cropped, convertToPixelCrop(crop, image.width, image.height))
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = 512
  const context = canvas.getContext('2d')
  if (!context) throw new Error('Canvas unavailable')
  context.drawImage(cropped, 0, 0, 512, 512)
  return new Promise((resolve, reject) => canvas.toBlob(blob => blob ? resolve(blob) : reject(new Error('Image conversion failed')), 'image/png'))
}

/** A private upload is staged here; only the parent's successful submit associates it. */
export function SkillAvatarEditor({ name, currentUrl, canRemove = false, disabled = false, onChange }: {
  name: string; currentUrl?: string | null; canRemove?: boolean; disabled?: boolean; onChange: (edit: AvatarEdit) => void
}) {
  const { t } = useTranslation()
  const [sourceUrl, setSourceUrl] = useState<string | null>(null)
  const [crop, setCrop] = useState<PercentCrop>()
  const [previewUrl, setPreviewUrl] = useState<string | null>(null)
  const [state, setState] = useState<'KEEP' | 'SELECTED' | 'UPLOADING' | 'READY' | 'ERROR' | 'REMOVE'>('KEEP')
  const [error, setError] = useState('')
  const generation = useRef(0)
  const image = useRef<HTMLImageElement>(null)
  useEffect(() => () => { generation.current += 1 }, [])
  useEffect(() => () => { if (sourceUrl) URL.revokeObjectURL(sourceUrl) }, [sourceUrl])

  function reset(remove = false) {
    generation.current += 1
    setSourceUrl(null)
    setPreviewUrl(null)
    setError('')
    setState(remove ? 'REMOVE' : 'KEEP')
    setCrop(undefined)
    if (inputRef.current) inputRef.current.value = ''
    onChange(remove ? { action: 'REMOVE', ready: true } : KEEP_AVATAR)
  }

  async function upload() {
    if (!image.current || !crop?.width || !crop.height) return
    const attempt = ++generation.current
    setState('UPLOADING')
    setError('')
    onChange({ action: 'REPLACE', ready: false })
    try {
      const blob = await exportCrop(image.current, crop)
      if (attempt !== generation.current) return
      const staged = await skillAvatarApi.stage(blob)
      if (attempt !== generation.current) return
      setPreviewUrl(staged.previewUrl)
      setState('READY')
      onChange({ action: 'REPLACE', uploadId: staged.uploadId, ready: true })
    } catch (cause) {
      if (attempt !== generation.current) return
      setState('ERROR')
      setError(cause instanceof Error ? cause.message : t('skillAvatar.failed'))
    }
  }

  const { getRootProps, getInputProps, inputRef } = useDropzone({
    accept: { 'image/png': ['.png'], 'image/jpeg': ['.jpg', '.jpeg'] },
    maxSize: 2 * 1024 * 1024,
    maxFiles: 1,
    multiple: false,
    disabled: disabled || state === 'UPLOADING',
    noClick: true,
    noKeyboard: true,
    onDrop: (accepted, rejected) => {
      generation.current += 1
      setPreviewUrl(null)
      setSourceUrl(null)
      setCrop(undefined)
      onChange({ action: 'REPLACE', ready: false })
      const file = accepted[0]
      if (rejected.length || !file || !['image/png', 'image/jpeg'].includes(file.type)) {
        setState('ERROR'); setError(t('skillAvatar.invalid')); return
      }
      setSourceUrl(URL.createObjectURL(file))
      setError(''); setState('SELECTED')
    },
  })

  return <section className="space-y-3" aria-label={t('skillAvatar.title')}>
    <h2 className="font-semibold">{t('skillAvatar.optionalTitle')}</h2>
    <p className="text-sm text-muted-foreground">{t('skillAvatar.stagedHelp')}</p>
    <div className="flex items-center gap-3">
      {state === 'READY' && previewUrl ? <img src={buildApiUrl(previewUrl)} alt={t('skillAvatar.cropPreview')} className="h-20 w-20 rounded-lg object-cover" />
        : <SkillAvatar name={name} url={state === 'REMOVE' ? null : currentUrl} size="h-20 w-20 text-2xl" />}
      <p role="status" className="text-sm text-muted-foreground">{t(`skillAvatar.uploadStates.${state}`)}</p>
    </div>
    <div {...getRootProps()}>
      <Input {...getInputProps({ 'aria-label': t('skillAvatar.choose'), style: undefined, tabIndex: 0, disabled: disabled || state === 'UPLOADING' })} />
    </div>
    {sourceUrl && state !== 'READY' && <div className="space-y-2">
      <ReactCrop crop={crop} aspect={1} keepSelection minWidth={16} minHeight={16}
        disabled={disabled || state === 'UPLOADING'}
        onChange={(_, percentCrop) => setCrop(percentCrop)}
        ariaLabels={{
          cropArea: t('skillAvatar.cropArea'),
          nwDragHandle: t('skillAvatar.cropHandles.nw'), nDragHandle: t('skillAvatar.cropHandles.n'),
          neDragHandle: t('skillAvatar.cropHandles.ne'), eDragHandle: t('skillAvatar.cropHandles.e'),
          seDragHandle: t('skillAvatar.cropHandles.se'), sDragHandle: t('skillAvatar.cropHandles.s'),
          swDragHandle: t('skillAvatar.cropHandles.sw'), wDragHandle: t('skillAvatar.cropHandles.w'),
        }}>
        <img ref={image} src={sourceUrl} alt={t('skillAvatar.cropPreview')} className="max-h-80 max-w-full"
          onLoad={event => {
            const { naturalWidth, naturalHeight } = event.currentTarget
            setCrop(centerCrop(makeAspectCrop({ unit: '%', width: Math.min(75, 75 * naturalHeight / naturalWidth) }, 1, naturalWidth, naturalHeight), naturalWidth, naturalHeight))
          }} onError={() => { setState('ERROR'); setError(t('skillAvatar.invalid')); setCrop(undefined) }} />
      </ReactCrop>
      <p className="text-sm text-muted-foreground">{t('skillAvatar.cropHelp')}</p>
      <Button type="button" disabled={disabled || state === 'UPLOADING' || !crop?.width || !crop.height} onClick={() => void upload()}>{t(state === 'ERROR' ? 'skillAvatar.uploadRetry' : 'skillAvatar.cropUpload')}</Button>
    </div>}
    <div className="flex flex-wrap gap-2">
      {state !== 'KEEP' && <Button type="button" variant="outline" disabled={disabled} onClick={() => reset()}>{t('skillAvatar.discardChange')}</Button>}
      {canRemove && state !== 'REMOVE' && <Button type="button" variant="outline" disabled={disabled} onClick={() => reset(true)}>{t('skillAvatar.remove')}</Button>}
    </div>
    {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
  </section>
}
