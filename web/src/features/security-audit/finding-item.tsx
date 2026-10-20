import { useTranslation } from 'react-i18next'
import type { SecurityFinding } from './types'
import { SeverityBadge } from './severity-badge'
import { cn } from '@/shared/lib/utils'

interface FindingItemProps {
  finding: SecurityFinding
  compact?: boolean
}

export function FindingItem({ finding, compact = false }: FindingItemProps) {
  const { t } = useTranslation()

  const location = [finding.filePath, finding.lineNumber].filter(Boolean).join(':')

  return (
    <div className={cn('space-y-2 rounded-xl border border-border/60 bg-card/70 p-4', compact && 'rounded-md p-3')}>
      <div className="flex items-center gap-2 flex-wrap">
        <SeverityBadge severity={finding.severity} />
        <code className="text-xs font-mono text-muted-foreground">{finding.ruleId}</code>
        {location && (
          <span className="text-xs text-muted-foreground">{location}</span>
        )}
      </div>

      <p className="text-sm text-foreground">{finding.title}</p>

      {finding.message && (
        <p className="text-sm text-muted-foreground">{finding.message}</p>
      )}

      {finding.codeSnippet && (
        <pre className={cn('overflow-x-auto rounded-lg bg-secondary/50 p-3 text-xs font-mono text-muted-foreground', compact && 'rounded-md p-2')}>
          {finding.codeSnippet}
        </pre>
      )}

      {finding.remediation && (
        <div className={cn('bg-secondary/50 rounded-xl p-3 text-sm text-muted-foreground', compact && 'rounded-md p-2')}>
          <span className="font-medium text-foreground">{t('securityAudit.remediation')}: </span>
          {finding.remediation}
        </div>
      )}
    </div>
  )
}
