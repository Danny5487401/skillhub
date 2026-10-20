import { useTranslation } from 'react-i18next'
import type { GithubSyncSkillPath } from '@/api/client'
import { formatLocalDateTime } from '@/shared/lib/date-time'
import { githubSyncStatusLabel } from './status-label'

export function GithubSyncPathIdentity({ path }: { path: GithubSyncSkillPath }) {
  const { t, i18n } = useTranslation()

  return (
    <div className="break-all font-mono text-xs">
      <div>{path.skillPath}</div>
      {path.skillId ? <div className="text-muted-foreground">{t('githubSync.boundSkillId')}: {path.skillId}</div> : null}
      {path.upstreamPresence ? (
        <div className={path.upstreamPresence === 'MISSING' ? 'text-destructive' : 'text-muted-foreground'}>
          {githubSyncStatusLabel(t, path.upstreamPresence)}
          {path.presenceCommitSha ? ` · ${path.presenceCommitSha.slice(0, 7)}` : ''}
          {path.missingSince ? ` · ${formatLocalDateTime(path.missingSince, i18n.language)}` : ''}
        </div>
      ) : null}
    </div>
  )
}
