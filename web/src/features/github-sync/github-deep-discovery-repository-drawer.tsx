import { useState } from 'react'
import { Link } from '@tanstack/react-router'
import { useTranslation } from 'react-i18next'
import { ExternalLink } from 'lucide-react'
import { GithubDeepDiscoveryPagination } from './github-deep-discovery-pagination'
import { formatLocalDateTime } from '@/shared/lib/date-time'
import { Dialog, DialogContent, DialogDescription, DialogHeader, DialogTitle } from '@/shared/ui/dialog'
import { useGithubDeepDiscoveryPaths, useGithubDeepDiscoveryProvenance, useGithubDeepDiscoveryRepository } from './use-github-deep-discovery'

const PAGE_SIZE = 20

export function GithubDeepDiscoveryRepositoryDrawer({ repositoryId, onClose, visible }: {
  repositoryId: number
  onClose: () => void
  visible: boolean
}) {
  const { t, i18n } = useTranslation()
  const [provenancePage, setProvenancePage] = useState(0)
  const [provenanceAsOf, setProvenanceAsOf] = useState<string>()
  const [pathPage, setPathPage] = useState(0)
  const [pathAsOf, setPathAsOf] = useState<string>()
  const detail = useGithubDeepDiscoveryRepository(repositoryId, visible)
  const provenance = useGithubDeepDiscoveryProvenance(repositoryId, provenancePage, provenanceAsOf, visible)
  const paths = useGithubDeepDiscoveryPaths(repositoryId, pathPage, pathAsOf, visible)
  const repository = detail.data?.repository
  const date = (value?: string | null) => formatLocalDateTime(value, i18n.language)
  const separator = i18n.language.startsWith('zh') ? '、' : ', '

  return <Dialog open onOpenChange={(open) => { if (!open) onClose() }}>
    <DialogContent className="github-sync-surface !left-auto !right-0 !top-0 !h-dvh !max-h-dvh !grid-rows-[auto_minmax(0,1fr)] !gap-0 !overflow-hidden w-[min(100vw,36rem)] !translate-x-0 !translate-y-0 !rounded-none p-0">
      <DialogHeader className="border-b px-5 py-4 text-left">
        <DialogTitle>{t('deepDiscovery.repositoryDetail')}</DialogTitle>
        <DialogDescription>{t('deepDiscovery.detailHelp')}</DialogDescription>
      </DialogHeader>
      <div className="min-h-0 space-y-5 overflow-y-auto overscroll-contain px-5 pb-6 pt-5">
        {detail.isLoading ? <p className="text-sm text-muted-foreground">{t('deepDiscovery.loading')}</p> : null}
        {detail.error ? <p role="alert" className="text-sm text-destructive">{t('deepDiscovery.loadFailed')}: {detail.error.message}</p> : null}
        {repository ? <>
          <div className="space-y-2">
            <a className="inline-flex max-w-full items-center gap-2 break-all text-lg font-semibold text-primary hover:underline"
              href={`https://github.com/${encodeURIComponent(repository.owner ?? '')}/${encodeURIComponent(repository.name ?? '')}`}
              target="_blank" rel="noopener noreferrer">{repository.owner}/{repository.name}<ExternalLink className="h-4 w-4 shrink-0" aria-hidden="true" /></a>
            <div className="flex flex-wrap gap-2 text-xs">
              <span className="sync-state" data-tone={repository.status === 'VALID' ? 'teal' : repository.status === 'VALIDATION_FAILED' ? 'red' : 'amber'}>{t(`deepDiscovery.status.${repository.status}`, { defaultValue: repository.status ?? '—' })}</span>
              {repository.paused ? <span className="sync-state" data-tone="amber">{t('deepDiscovery.paused')}</span> : null}
              {repository.sourceId ? <span className="sync-state" data-tone="blue">{t('deepDiscovery.registeredSource')}</span> : null}
            </div>
            <p className="text-xs text-muted-foreground">{t('deepDiscovery.firstDiscovered')}: {date(repository.firstDiscoveredAt)} · {t('deepDiscovery.lastDiscovered')}: {date(repository.lastDiscoveredAt)}</p>
          </div>
          <section className="space-y-2 border-t pt-4">
            <h3 className="font-semibold">{t('deepDiscovery.provenance')}</h3>
            {provenance.isLoading ? <p className="text-sm text-muted-foreground">{t('deepDiscovery.loading')}</p> : null}
            {provenance.error ? <p role="alert" className="text-sm text-destructive">{t('deepDiscovery.loadFailed')}: {provenance.error.message}</p> : null}
            {provenance.data?.items?.length ? <div className="divide-y rounded border text-sm">{provenance.data.items.map((hit) => <div key={hit.id} className="grid gap-1 px-3 py-2 sm:grid-cols-[minmax(0,1fr)_auto]">
              <span>{t(`deepDiscovery.channel.${hit.channel}`, { defaultValue: hit.channel ?? '—' })}</span>
              <span className="text-xs text-muted-foreground">{date(hit.lastSeenAt)}</span>
              {hit.seedRepositoryId ? <span className="text-xs text-muted-foreground sm:col-span-2">{t('deepDiscovery.seedRepository')}: #{hit.seedRepositoryId}</span> : null}
            </div>)}</div> : !provenance.isLoading && !provenance.error ? <p className="text-sm text-muted-foreground">{t('deepDiscovery.noProvenance')}</p> : null}
            {provenance.data ? <GithubDeepDiscoveryPagination page={provenancePage} total={provenance.data.total ?? 0}
              size={PAGE_SIZE} detail
              onPageChange={(next) => { setProvenanceAsOf(provenanceAsOf ?? provenance.data?.asOf); setProvenancePage(next) }} /> : null}
          </section>
          <section className="space-y-2 border-t pt-4">
            <h3 className="font-semibold">{t('deepDiscovery.checkResult')}</h3>
            <dl className="grid grid-cols-2 gap-3 rounded border p-3 text-sm">
              <div><dt className="text-xs text-muted-foreground">{t('deepDiscovery.lastChecked')}</dt><dd>{date(detail.data?.lastCheckedAt)}</dd></div>
              <div><dt className="text-xs text-muted-foreground">{t('deepDiscovery.nextEligible')}</dt><dd>{date(detail.data?.nextEligibleAt)}</dd></div>
              <div className="col-span-2"><dt className="text-xs text-muted-foreground">{t('deepDiscovery.processingStatus')}</dt><dd>{t(`deepDiscovery.reason.${detail.data?.notImportedReason}`, { defaultValue: detail.data?.notImportedReason ?? '—' })}</dd></div>
            </dl>
            {detail.data?.waits?.map((wait, index) => <div key={`${wait.reason}:${index}`} className="rounded border-l-2 border-amber-500 bg-amber-500/5 px-3 py-2 text-sm">
              <p className="font-medium">{t(`deepDiscovery.reason.${wait.reason}`, { defaultValue: wait.reason ?? '—' })}</p>
              <p className="text-xs text-muted-foreground">{t('deepDiscovery.affectedWork')}: {(wait.affectedWork ?? []).map((work) => t(`deepDiscovery.work.${work}`, { defaultValue: work })).join(separator)}</p>
              {wait.retryAt ? <p className="text-xs text-muted-foreground">{t('deepDiscovery.retryAt')}: {date(wait.retryAt)}</p> : null}
              <p className="text-xs text-muted-foreground">{i18n.language.startsWith('zh') && wait.recoveryCondition ? wait.recoveryCondition : t(`deepDiscovery.recovery.${wait.reason}`, { defaultValue: t('deepDiscovery.autoResume') })}</p>
            </div>)}
          </section>
          <section className="space-y-2 border-t pt-4">
            <h3 className="font-semibold">{t('deepDiscovery.pathResults')} {paths.data ? `(${paths.data.total ?? 0})` : ''}</h3>
            {paths.isLoading ? <p className="text-sm text-muted-foreground">{t('deepDiscovery.loading')}</p> : null}
            {paths.error ? <p role="alert" className="text-sm text-destructive">{t('deepDiscovery.loadFailed')}: {paths.error.message}</p> : null}
            {paths.data?.items?.length ? <div className="divide-y rounded border text-sm">{paths.data.items.map((path, index) => <div key={`${path.skillPath}:${index}`} className="space-y-1 px-3 py-2">
              <p className="break-all font-medium">{path.skillPath}</p>
              <p className="text-xs text-muted-foreground">{t(`deepDiscovery.pathOutcome.${path.outcome}`, { defaultValue: path.outcome ?? '—' })}{path.reasonCode ? ` · ${t(`githubSync.codes.${path.reasonCode}`, { defaultValue: path.reasonCode })}` : ''}</p>
              {path.reviewId ? <Link to="/dashboard/reviews/$id" params={{ id: String(path.reviewId) }} className="text-xs text-primary hover:underline">{t('deepDiscovery.viewReview')}</Link> : null}
            </div>)}</div> : !paths.isLoading && !paths.error ? <p className="text-sm text-muted-foreground">{t('deepDiscovery.noPaths')}</p> : null}
            {paths.data ? <GithubDeepDiscoveryPagination page={pathPage} total={paths.data.total ?? 0}
              size={PAGE_SIZE} detail
              onPageChange={(next) => { setPathAsOf(pathAsOf ?? paths.data?.asOf); setPathPage(next) }} /> : null}
          </section>
          <div className="flex flex-wrap gap-2 border-t pt-4">
            {repository.sourceId ? <Link to="/dashboard/github-sync" search={{ sourceId: repository.sourceId }} className="text-sm text-primary hover:underline">{t('deepDiscovery.viewSource')}</Link> : null}
            {detail.data?.latestRunId ? <Link to="/dashboard/github-sync" search={{ runId: detail.data.latestRunId }} className="text-sm text-primary hover:underline">{t('deepDiscovery.viewRun')}</Link> : null}
            <Link to="/dashboard/reviews" search={{ type: 'sync' }} className="text-sm text-primary hover:underline">{t('deepDiscovery.viewReviewQueue')}</Link>
          </div>
        </> : null}
      </div>
    </DialogContent>
  </Dialog>
}
