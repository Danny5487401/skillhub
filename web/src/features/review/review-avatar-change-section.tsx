import { useTranslation } from 'react-i18next'
import { buildApiUrl } from '@/api/client'
import type { ReviewAvatarChange } from '@/api/types'
import { SkillAvatar } from '@/features/skill/skill-avatar'
import { Card } from '@/shared/ui/card'

/** Avatar changes share this task's existing approval and rejection actions. */
export function ReviewAvatarChangeSection({ change, name }: { change: ReviewAvatarChange; name: string }) {
  const { t } = useTranslation()
  return (
    <Card className="space-y-4 p-6 md:p-8">
      <h2 className="text-xl font-semibold">{t(change.removal ? 'review.avatarRemoval' : 'review.avatarUpdate')}</h2>
      <p className="text-sm text-muted-foreground">{t('review.avatarUnifiedDecision')}</p>
      <div className="flex flex-wrap gap-6">
        <div className="space-y-2">
          <p className="text-sm text-muted-foreground">{t('review.currentAvatar')}</p>
          <SkillAvatar name={name} url={change.currentAvatarUrl} size="h-16 w-16 text-xl" />
        </div>
        <div className="space-y-2">
          <p className="text-sm text-muted-foreground">{t('review.requestedAvatar')}</p>
          {change.removal ? <SkillAvatar name={name} size="h-16 w-16 text-xl" />
            : change.candidatePreviewUrl ? <img src={buildApiUrl(change.candidatePreviewUrl)} alt={t('skillAvatar.candidatePreview')} className="h-16 w-16 rounded-lg object-cover" />
              : <p className="text-sm text-muted-foreground">{t('review.avatarPreviewUnavailable')}</p>}
        </div>
      </div>
      {!change.removal && <div className="space-y-1 text-sm text-muted-foreground">
        {change.machineStatus && <p>{t(`skillAvatar.machine.${change.machineStatus}`)}</p>}
        {change.reviewStatus && <p>{t(`review.avatarReviewStatus.${change.reviewStatus}`)}</p>}
      </div>}
    </Card>
  )
}
