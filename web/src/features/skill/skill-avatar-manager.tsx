import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { skillAvatarApi } from '@/api/client'
import { Button } from '@/shared/ui/button'

export function SkillAvatarManager({ namespace, slug, disabled = false }: { namespace: string; slug: string; disabled?: boolean }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const key = ['skill-avatar', namespace, slug]
  const status = useQuery({ queryKey: key, queryFn: () => skillAvatarApi.status(namespace, slug), enabled: !!namespace && !!slug })
  const [error, setError] = useState('')
  const mutation = useMutation({
    mutationFn: async (action: { kind: 'appeal' | 'retry'; id: number }) => action.kind === 'appeal'
      ? skillAvatarApi.appeal(namespace, slug, action.id)
      : skillAvatarApi.retry(namespace, slug, action.id),
    onSuccess: async () => {
      setError('')
      await Promise.all([queryClient.invalidateQueries({ queryKey: key }), queryClient.invalidateQueries({ queryKey: ['skill'] }), queryClient.invalidateQueries({ queryKey: ['skills'] }), queryClient.invalidateQueries({ queryKey: ['skill-avatar-review'] })])
    },
    onError: (cause) => setError(cause instanceof Error ? cause.message : t('skillAvatar.failed')),
  })
  const candidate = status.data
  const retryCoolingDown = !!candidate?.machineNextRetryAt && Date.parse(candidate.machineNextRetryAt) > Date.now()
  if (!candidate || !['PENDING', 'REJECTED'].includes(candidate.candidateStatus ?? '')) return null
  return (
    <div className="space-y-2 text-sm">
      {candidate?.candidateStatus && <p role="status" className="text-sm">{t('skillAvatar.status')}: {t(`skillAvatar.states.${candidate.candidateStatus}`)}</p>}
      {candidate?.reviewReason && <p className="text-sm">{candidate.reviewReason}</p>}
      {candidate?.machineStatus === 'ERROR' && retryCoolingDown && <p className="text-sm text-muted-foreground">{t('skillAvatar.retryAfter', { time: new Date(candidate.machineNextRetryAt!).toLocaleString() })}</p>}
      {candidate?.machineStatus === 'ERROR' && candidate.automaticRetryExhausted && <p className="text-sm text-muted-foreground">{t('skillAvatar.automaticRetryStopped')}</p>}
      <div className="flex flex-wrap gap-2">
        {candidate?.candidateStatus === 'PENDING' && candidate.machineStatus === 'BLOCKED'
          && candidate.reviewStatus === 'NOT_REQUIRED' && candidate.candidateId
          && <Button variant="outline" disabled={disabled || mutation.isPending} onClick={() => mutation.mutate({ kind: 'appeal', id: candidate.candidateId! })}>{t('skillAvatar.appeal')}</Button>}
        {candidate?.machineStatus === 'ERROR' && candidate.candidateId && <Button variant="outline" disabled={disabled || mutation.isPending || retryCoolingDown} onClick={() => mutation.mutate({ kind: 'retry', id: candidate.candidateId! })}>{t('skillAvatar.retry')}</Button>}
      </div>
      {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
    </div>
  )
}
