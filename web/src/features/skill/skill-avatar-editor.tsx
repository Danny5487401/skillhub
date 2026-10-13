import { useEffect, useRef, useState } from 'react'
import { useTranslation } from 'react-i18next'
import { buildApiUrl, skillAvatarApi } from '@/api/client'
import { Button } from '@/shared/ui/button'
import { Input } from '@/shared/ui/input'
import { SkillAvatar } from './skill-avatar'

export type AvatarEdit = { action: 'KEEP' | 'REPLACE' | 'REMOVE'; uploadId?: string; ready: boolean }
export const KEEP_AVATAR: AvatarEdit = { action: 'KEEP', ready: true }

async function cropSquare(source: string, zoom: number): Promise<Blob> {
  const image = new Image()
  image.src = source
  await image.decode()
  const canvas = document.createElement('canvas')
  canvas.width = canvas.height = 512
  const context = canvas.getContext('2d')
  if (!context) throw new Error('Canvas unavailable')
  const side = Math.min(image.naturalWidth, image.naturalHeight) / zoom
  context.drawImage(image, (image.naturalWidth - side) / 2, (image.naturalHeight - side) / 2, side, side, 0, 0, 512, 512)
  return new Promise((resolve, reject) => canvas.toBlob(blob => blob ? resolve(blob) : reject(new Error('Image conversion failed')), 'image/png'))
}

/** A private upload is staged here; only the parent's successful submit associates it. */
export function SkillAvatarEditor({ name, currentUrl, canRemove = false, disabled = false, onChange }: {
  name: string; currentUrl?: string | null; canRemove?: boolean; disabled?: boolean; onChange: (edit: AvatarEdit) => void
}) {
  const { t } = useTranslation()
  const [sourceUrl, setSourceUrl] = useState<string | null>(null)
  const [zoom, setZoom] = useState(1)
  const [previewUrl, setPreviewUrl] = useState<string | null>(null)
  const [state, setState] = useState<'KEEP' | 'SELECTED' | 'UPLOADING' | 'READY' | 'ERROR' | 'REMOVE'>('KEEP')
  const [error, setError] = useState('')
  const generation = useRef(0)
  const input = useRef<HTMLInputElement>(null)
  useEffect(() => () => { generation.current += 1 }, [])
  useEffect(() => () => { if (sourceUrl) URL.revokeObjectURL(sourceUrl) }, [sourceUrl])

  function reset(remove = false) {
    generation.current += 1
    setSourceUrl(null)
    setPreviewUrl(null)
    setError('')
    setState(remove ? 'REMOVE' : 'KEEP')
    if (input.current) input.current.value = ''
    onChange(remove ? { action: 'REMOVE', ready: true } : KEEP_AVATAR)
  }

  async function upload() {
    if (!sourceUrl) return
    const attempt = ++generation.current
    setState('UPLOADING')
    setError('')
    onChange({ action: 'REPLACE', ready: false })
    try {
      const staged = await skillAvatarApi.stage(await cropSquare(sourceUrl, zoom))
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

  return <section className="space-y-3" aria-label={t('skillAvatar.title')}>
    <h2 className="font-semibold">{t('skillAvatar.optionalTitle')}</h2>
    <p className="text-sm text-muted-foreground">{t('skillAvatar.stagedHelp')}</p>
    <div className="flex items-center gap-3">
      {state === 'READY' && previewUrl ? <img src={buildApiUrl(previewUrl)} alt={t('skillAvatar.cropPreview')} className="h-20 w-20 rounded-lg object-cover" />
        : <SkillAvatar name={name} url={state === 'REMOVE' ? null : currentUrl} size="h-20 w-20 text-2xl" />}
      <p role="status" className="text-sm text-muted-foreground">{t(`skillAvatar.uploadStates.${state}`)}</p>
    </div>
    <Input ref={input} type="file" disabled={disabled || state === 'UPLOADING'} accept="image/png,image/jpeg" aria-label={t('skillAvatar.choose')} onChange={event => {
      const file = event.target.files?.[0]
      if (!file) return
      generation.current += 1
      setPreviewUrl(null)
      setSourceUrl(null)
      onChange({ action: 'REPLACE', ready: false })
      if (!['image/png', 'image/jpeg'].includes(file.type) || file.size > 2 * 1024 * 1024) {
        setState('ERROR'); setError(t('skillAvatar.invalid')); return
      }
      setSourceUrl(URL.createObjectURL(file))
      setZoom(1); setError(''); setState('SELECTED')
    }} />
    {sourceUrl && state !== 'READY' && <div className="space-y-2">
      <div className="h-40 w-40 overflow-hidden rounded-lg border"><img src={sourceUrl} alt={t('skillAvatar.cropPreview')} className="h-full w-full object-cover" style={{ transform: `scale(${zoom})` }} /></div>
      <label className="block text-sm">{t('skillAvatar.zoom')} <input type="range" min="1" max="3" step="0.1" value={zoom} disabled={disabled || state === 'UPLOADING'} onChange={event => setZoom(Number(event.target.value))} /></label>
      <Button type="button" disabled={disabled || state === 'UPLOADING'} onClick={() => void upload()}>{t(state === 'ERROR' ? 'skillAvatar.uploadRetry' : 'skillAvatar.cropUpload')}</Button>
    </div>}
    <div className="flex flex-wrap gap-2">
      {state !== 'KEEP' && <Button type="button" variant="outline" disabled={disabled} onClick={() => reset()}>{t('skillAvatar.discardChange')}</Button>}
      {canRemove && state !== 'REMOVE' && <Button type="button" variant="outline" disabled={disabled} onClick={() => reset(true)}>{t('skillAvatar.remove')}</Button>}
    </div>
    {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
  </section>
}
