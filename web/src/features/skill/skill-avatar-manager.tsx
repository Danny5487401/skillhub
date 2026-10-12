import { useEffect, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { buildApiUrl, skillAvatarApi } from '@/api/client'
import { Button } from '@/shared/ui/button'
import { Card } from '@/shared/ui/card'
import { Input } from '@/shared/ui/input'

function cropSquare(image: HTMLImageElement, zoom: number): Promise<Blob> {
  const canvas = document.createElement('canvas')
  canvas.width = 512
  canvas.height = 512
  const context = canvas.getContext('2d')
  if (!context) return Promise.reject(new Error('Canvas unavailable'))
  const side = Math.min(image.naturalWidth, image.naturalHeight) / zoom
  context.drawImage(image, (image.naturalWidth - side) / 2, (image.naturalHeight - side) / 2, side, side, 0, 0, 512, 512)
  return new Promise((resolve, reject) => canvas.toBlob((blob) => blob ? resolve(blob) : reject(new Error('Image conversion failed')), 'image/png'))
}

export function SkillAvatarManager({ namespace, slug }: { namespace: string; slug: string }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const key = ['skill-avatar', namespace, slug]
  const status = useQuery({ queryKey: key, queryFn: () => skillAvatarApi.status(namespace, slug) })
  const [sourceUrl, setSourceUrl] = useState<string | null>(null)
  const [zoom, setZoom] = useState(1)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  useEffect(() => () => { if (sourceUrl) URL.revokeObjectURL(sourceUrl) }, [sourceUrl])
  const mutation = useMutation({
    mutationFn: async (action: { kind: 'upload'; blob: Blob } | { kind: 'remove' | 'appeal' | 'retry'; id?: number }) => {
      if (action.kind === 'upload') return skillAvatarApi.upload(namespace, slug, action.blob)
      if (action.kind === 'remove') return skillAvatarApi.delete(namespace, slug)
      if (action.kind === 'appeal') return skillAvatarApi.appeal(namespace, slug, action.id!)
      return skillAvatarApi.retry(namespace, slug, action.id!)
    },
    onSuccess: async () => {
      setError('')
      setSourceUrl(null)
      await Promise.all([queryClient.invalidateQueries({ queryKey: key }), queryClient.invalidateQueries({ queryKey: ['skill'] }), queryClient.invalidateQueries({ queryKey: ['skills'] })])
    },
    onError: (cause) => setError(cause instanceof Error ? cause.message : t('skillAvatar.failed')),
  })
  async function submit() {
    if (!sourceUrl) return
    setBusy(true)
    try {
      const image = new Image()
      image.src = sourceUrl
      await image.decode()
      await mutation.mutateAsync({ kind: 'upload', blob: await cropSquare(image, zoom) })
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : t('skillAvatar.failed'))
    } finally { setBusy(false) }
  }
  const candidate = status.data
  return (
    <Card className="space-y-3 p-4">
      <h2 className="font-semibold">{t('skillAvatar.title')}</h2>
      <p className="text-sm text-muted-foreground">{t('skillAvatar.ownerHelp')}</p>
      {candidate?.candidateStatus && <p role="status" className="text-sm">{t('skillAvatar.status')}: {t(`skillAvatar.states.${candidate.candidateStatus}`)} · {t(`skillAvatar.machine.${candidate.machineStatus ?? 'SKIPPED'}`)}</p>}
      {candidate?.reviewReason && <p className="text-sm">{candidate.reviewReason}</p>}
      {candidate?.candidateUrl && <img src={buildApiUrl(candidate.candidateUrl)} alt={t('skillAvatar.candidatePreview')} className="h-24 w-24 rounded-lg object-cover" />}
      <Input type="file" accept="image/png,image/jpeg" aria-label={t('skillAvatar.choose')} onChange={(event) => {
        const file = event.target.files?.[0]
        if (!file) return
        if (!['image/png', 'image/jpeg'].includes(file.type) || file.size > 2 * 1024 * 1024) { setError(t('skillAvatar.invalid')); return }
        if (sourceUrl) URL.revokeObjectURL(sourceUrl)
        setSourceUrl(URL.createObjectURL(file))
        setZoom(1)
        setError('')
      }} />
      {sourceUrl && <div className="space-y-2">
        <div className="h-40 w-40 overflow-hidden rounded-lg border"><img src={sourceUrl} alt={t('skillAvatar.cropPreview')} className="h-full w-full object-cover" style={{ transform: `scale(${zoom})` }} /></div>
        <label className="block text-sm">{t('skillAvatar.zoom')} <input type="range" min="1" max="3" step="0.1" value={zoom} onChange={(event) => setZoom(Number(event.target.value))} /></label>
        <Button disabled={busy || mutation.isPending} onClick={() => void submit()}>{t('skillAvatar.submit')}</Button>
      </div>}
      <div className="flex flex-wrap gap-2">
        {(candidate?.avatarUrl || candidate?.candidateStatus === 'PENDING') && <Button variant="outline" disabled={mutation.isPending} onClick={() => void mutation.mutateAsync({ kind: 'remove' })}>{t('skillAvatar.remove')}</Button>}
        {candidate?.candidateStatus === 'REJECTED' && candidate.candidateId && <Button variant="outline" disabled={mutation.isPending} onClick={() => void mutation.mutateAsync({ kind: 'appeal', id: candidate.candidateId! })}>{t('skillAvatar.appeal')}</Button>}
        {candidate?.machineStatus === 'ERROR' && candidate.candidateId && <Button variant="outline" disabled={mutation.isPending} onClick={() => void mutation.mutateAsync({ kind: 'retry', id: candidate.candidateId! })}>{t('skillAvatar.retry')}</Button>}
      </div>
      {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
    </Card>
  )
}
