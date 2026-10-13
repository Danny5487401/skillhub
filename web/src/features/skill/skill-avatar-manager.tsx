import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { buildApiUrl, skillAvatarApi } from '@/api/client'
import { Button } from '@/shared/ui/button'
import { Card } from '@/shared/ui/card'
import { SkillAvatarEditor, KEEP_AVATAR, type AvatarEdit } from './skill-avatar-editor'

export function SkillAvatarManager({ namespace, slug, name = slug, readOnly = false }: { namespace: string; slug: string; name?: string; readOnly?: boolean }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const key = ['skill-avatar', namespace, slug]
  const status = useQuery({ queryKey: key, queryFn: () => skillAvatarApi.status(namespace, slug) })
  const [edit, setEdit] = useState<AvatarEdit>(KEEP_AVATAR)
  const [error, setError] = useState('')
  const [editorVersion, setEditorVersion] = useState(0)
  const mutation = useMutation({
    mutationFn: async (action: { kind: 'upload'; uploadId: string } | { kind: 'remove' | 'appeal' | 'retry'; id?: number }) => {
      if (action.kind === 'upload') return skillAvatarApi.submit(namespace, slug, action.uploadId)
      if (action.kind === 'remove') return skillAvatarApi.delete(namespace, slug)
      if (action.kind === 'appeal') return skillAvatarApi.appeal(namespace, slug, action.id!)
      return skillAvatarApi.retry(namespace, slug, action.id!)
    },
    onSuccess: async (_data, action) => {
      setError('')
      if (action.kind === 'upload' || action.kind === 'remove') {
        setEdit(KEEP_AVATAR)
        setEditorVersion(value => value + 1)
      }
      await Promise.all([queryClient.invalidateQueries({ queryKey: key }), queryClient.invalidateQueries({ queryKey: ['skill'] }), queryClient.invalidateQueries({ queryKey: ['skills'] }), queryClient.invalidateQueries({ queryKey: ['skill-avatar-review'] })])
    },
    onError: (cause) => setError(cause instanceof Error ? cause.message : t('skillAvatar.failed')),
  })
  const candidate = status.data
  const retryCoolingDown = !!candidate?.machineNextRetryAt && Date.parse(candidate.machineNextRetryAt) > Date.now()
  return (
    <Card className="space-y-3 p-4">
      <h2 className="font-semibold">{t('skillAvatar.title')}</h2>
      <p className="text-sm text-muted-foreground">{t(readOnly ? 'skillAvatar.detailHelp' : 'skillAvatar.ownerHelp')}</p>
      {candidate?.candidateStatus && <p role="status" className="text-sm">{t('skillAvatar.status')}: {t(`skillAvatar.states.${candidate.candidateStatus}`)} · {t(`skillAvatar.machine.${candidate.machineStatus ?? 'SKIPPED'}`)}</p>}
      {candidate?.reviewReason && <p className="text-sm">{candidate.reviewReason}</p>}
      {candidate?.machineStatus === 'ERROR' && retryCoolingDown && <p className="text-sm text-muted-foreground">{t('skillAvatar.retryAfter', { time: new Date(candidate.machineNextRetryAt!).toLocaleString() })}</p>}
      {candidate?.machineStatus === 'ERROR' && candidate.automaticRetryExhausted && <p className="text-sm text-muted-foreground">{t('skillAvatar.automaticRetryStopped')}</p>}
      {candidate?.candidateUrl && <img src={buildApiUrl(candidate.candidateUrl)} alt={t('skillAvatar.candidatePreview')} className="h-24 w-24 rounded-lg object-cover" />}
      {!readOnly && <>
        <SkillAvatarEditor key={editorVersion} name={name} currentUrl={candidate?.avatarUrl} canRemove={!!candidate?.avatarUrl || candidate?.candidateStatus === 'PENDING'} disabled={mutation.isPending || status.isLoading || status.isError} onChange={setEdit} />
        <Button disabled={edit.action === 'KEEP' || !edit.ready || mutation.isPending || status.isLoading || status.isError} onClick={() => mutation.mutate(edit.action === 'REMOVE' ? { kind: 'remove' } : { kind: 'upload', uploadId: edit.uploadId! })}>{t('skillAvatar.saveChanges')}</Button>
      </>}
      {status.isError && <p role="alert" className="text-sm text-destructive">{t('skillAvatar.failed')}</p>}
      <div className="flex flex-wrap gap-2">
        {!readOnly && candidate?.candidateStatus === 'PENDING' && candidate.machineStatus === 'BLOCKED'
          && candidate.reviewStatus === 'NOT_REQUIRED' && candidate.candidateId
          && <Button variant="outline" disabled={mutation.isPending} onClick={() => mutation.mutate({ kind: 'appeal', id: candidate.candidateId! })}>{t('skillAvatar.appeal')}</Button>}
        {!readOnly && candidate?.machineStatus === 'ERROR' && candidate.candidateId && <Button variant="outline" disabled={mutation.isPending || retryCoolingDown} onClick={() => mutation.mutate({ kind: 'retry', id: candidate.candidateId! })}>{t('skillAvatar.retry')}</Button>}
      </div>
      {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
    </Card>
  )
}
