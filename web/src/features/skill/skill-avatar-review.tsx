import { useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { buildApiUrl, skillAvatarApi } from '@/api/client'
import { Button } from '@/shared/ui/button'
import { Card } from '@/shared/ui/card'
import { Input } from '@/shared/ui/input'

export function SkillAvatarReviewQueue({ namespace }: { namespace?: string }) {
  const { t } = useTranslation()
  const queryClient = useQueryClient()
  const [reasons, setReasons] = useState<Record<number, string>>({})
  const [error, setError] = useState('')
  const queue = useQuery({ queryKey: ['skill-avatar-review'], queryFn: skillAvatarApi.queue })
  const decide = useMutation({
    mutationFn: (input: { namespace: string; slug: string; candidateId: number; approved: boolean; reason: string }) =>
      skillAvatarApi.decide(input.namespace, input.slug, input.candidateId, input.approved, input.reason),
    onSuccess: async (_data, input) => {
      setError('')
      await Promise.all([
        queryClient.invalidateQueries({ queryKey: ['skill-avatar-review'] }),
        queryClient.invalidateQueries({ queryKey: ['skill-avatar', input.namespace, input.slug] }),
        queryClient.invalidateQueries({ queryKey: ['skills'] }),
      ])
    },
    onError: (cause) => setError(cause instanceof Error ? cause.message : t('skillAvatar.failed')),
  })
  const items = queue.data?.filter((item) => !namespace || item.namespace === namespace) ?? []
  return <Card className="space-y-4 p-5">
    <h2 className="text-lg font-semibold">{t('skillAvatar.reviewTitle')}</h2>
    {queue.isLoading && <p className="text-sm text-muted-foreground">{t('skillAvatar.loading')}</p>}
    {queue.isError && <p role="alert" className="text-sm text-destructive">{t('skillAvatar.failed')}</p>}
    {!queue.isLoading && !queue.isError && items.length === 0 && <p className="text-sm text-muted-foreground">{t('skillAvatar.emptyQueue')}</p>}
    {items.map((item) => item.candidateId && <div key={item.candidateId} className="flex flex-wrap items-start gap-4 rounded-lg border p-4">
      {item.candidateUrl && <img src={buildApiUrl(item.candidateUrl)} alt={t('skillAvatar.candidatePreview')} className="h-20 w-20 rounded-lg object-cover" />}
      <div className="min-w-0 flex-1 space-y-2">
        <p className="font-medium">@{item.namespace}/{item.slug}</p>
        <p className="text-sm text-muted-foreground">{t(`skillAvatar.machine.${item.machineStatus ?? 'SKIPPED'}`)}</p>
        <Input value={reasons[item.candidateId] ?? ''} onChange={(event) => setReasons({ ...reasons, [item.candidateId!]: event.target.value })} placeholder={t('skillAvatar.reason')} aria-label={t('skillAvatar.reason')} />
        <div className="flex gap-2">
          <Button disabled={decide.isPending || !(reasons[item.candidateId!] ?? '').trim()} onClick={() => decide.mutate({ namespace: item.namespace, slug: item.slug, candidateId: item.candidateId!, approved: true, reason: reasons[item.candidateId!] ?? '' })}>{t('skillAvatar.approve')}</Button>
          <Button variant="outline" disabled={decide.isPending || !(reasons[item.candidateId!] ?? '').trim()} onClick={() => decide.mutate({ namespace: item.namespace, slug: item.slug, candidateId: item.candidateId!, approved: false, reason: reasons[item.candidateId!] ?? '' })}>{t('skillAvatar.reject')}</Button>
        </div>
      </div>
    </div>)}
    {error && <p role="alert" className="text-sm text-destructive">{error}</p>}
  </Card>
}
