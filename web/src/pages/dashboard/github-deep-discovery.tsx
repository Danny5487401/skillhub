import { useEffect, useState, type FormEvent } from 'react'
import { Link } from '@tanstack/react-router'
import { useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { ArrowRight, Compass, RefreshCw, Settings2 } from 'lucide-react'
import type { GithubDeepDiscoveryRepositoryQuery, GithubDeepDiscoveryWait } from '@/api/client'
import { DashboardPageHeader } from '@/shared/components/dashboard-page-header'
import { formatLocalDateTime } from '@/shared/lib/date-time'
import { Button } from '@/shared/ui/button'
import { Card } from '@/shared/ui/card'
import { Input } from '@/shared/ui/input'
import { Tabs, TabsContent, TabsList, TabsTrigger } from '@/shared/ui/tabs'
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from '@/shared/ui/table'
import { GithubDeepDiscoveryConfig } from '@/features/github-sync/github-deep-discovery-config'
import { GithubDeepDiscoveryPagination } from '@/features/github-sync/github-deep-discovery-pagination'
import { GithubDeepDiscoveryRepositoryDrawer } from '@/features/github-sync/github-deep-discovery-repository-drawer'
import {
  DEEP_DISCOVERY_QUERY_KEY,
  useDeepDiscoveryVisible,
  useGithubDeepDiscoveryConfiguration,
  useGithubDeepDiscoveryProgress,
  useGithubDeepDiscoveryRepositories,
  useGithubDeepDiscoverySummary,
} from '@/features/github-sync/use-github-deep-discovery'
import '@/features/github-sync/github-sync-colors.css'

const PAGE_SIZE = 20
type RepositoryStatus = GithubDeepDiscoveryRepositoryQuery['status']
type Channel = GithubDeepDiscoveryRepositoryQuery['channel']

function QueryMessage({ loading, error, empty }: { loading: boolean; error: Error | null; empty?: boolean }) {
  const { t } = useTranslation()
  if (loading) return <p className="p-5 text-sm text-muted-foreground">{t('deepDiscovery.loading')}</p>
  if (error) return <p role="alert" className="p-5 text-sm text-destructive">{t('deepDiscovery.loadFailed')}: {error.message}</p>
  if (empty) return <p className="p-5 text-sm text-muted-foreground">{t('deepDiscovery.empty')}</p>
  return null
}

function Metric({ label, value, note }: { label: string; value: number | null | undefined; note?: string }) {
  return <div className="min-w-0 p-4">
    <p className="text-xs text-muted-foreground">{label}</p>
    <p className="mt-1 text-2xl font-semibold tabular-nums">{value == null ? '—' : value.toLocaleString()}</p>
    {note ? <p className="mt-1 text-xs text-muted-foreground">{note}</p> : null}
  </div>
}

function tone(state?: string) {
  if (state === 'RUNNING' || state === 'VALID' || state === 'AVAILABLE') return 'teal'
  if (state === 'UNREACHABLE' || state === 'VALIDATION_FAILED') return 'red'
  if (state === 'IDLE' || state === 'NO_VALID_SKILL') return 'blue'
  return 'amber'
}

function WaitNotice({ wait, onConfigure }: { wait: GithubDeepDiscoveryWait; onConfigure: () => void }) {
  const { t, i18n } = useTranslation()
  const action = wait.action
  const separator = i18n.language.startsWith('zh') ? '、' : ', '
  return <div className="flex flex-wrap items-center justify-between gap-3 border-t border-amber-500/30 bg-amber-500/5 px-4 py-3 text-sm">
    <div className="min-w-0">
      <p className="font-medium" style={{ color: 'var(--sync-amber)' }}>{t(`deepDiscovery.reason.${wait.reason}`, { defaultValue: wait.reason ?? '—' })}</p>
      <p className="text-xs text-muted-foreground">{t('deepDiscovery.affectedWork')}: {(wait.affectedWork ?? []).map((work) => t(`deepDiscovery.work.${work}`, { defaultValue: work })).join(separator)}</p>
      <p className="text-xs text-muted-foreground">{i18n.language.startsWith('zh') && wait.recoveryCondition ? wait.recoveryCondition : t(`deepDiscovery.recovery.${wait.reason}`, { defaultValue: t('deepDiscovery.autoResume') })}{wait.retryAt ? ` · ${t('deepDiscovery.retryAt')}: ${formatLocalDateTime(wait.retryAt, i18n.language)}` : ''}</p>
      {wait.continuingWork?.length ? <p className="text-xs text-muted-foreground">{t('deepDiscovery.continuingWork')}: {wait.continuingWork.map((work) => t(`deepDiscovery.work.${work}`, { defaultValue: work })).join(separator)}</p> : null}
    </div>
    {action === 'EDIT_CONFIGURATION' ? <Button size="sm" variant="outline" onClick={onConfigure}>{t('deepDiscovery.configure')}</Button> : null}
    {action === 'MANAGE_ADMISSION' || action === 'MANAGE_CREDENTIALS' ? <Link to="/dashboard/github-sync" className="text-sm text-primary hover:underline">{t(action === 'MANAGE_ADMISSION' ? 'deepDiscovery.viewCapacity' : 'deepDiscovery.viewCredentials')} <ArrowRight className="inline h-4 w-4" /></Link> : null}
  </div>
}

export function GithubDeepDiscoveryPage() {
  const { t, i18n } = useTranslation()
  const queryClient = useQueryClient()
  const visible = useDeepDiscoveryVisible()
  const [days, setDays] = useState(7)
  const [tab, setTab] = useState('progress')
  const [configOpen, setConfigOpen] = useState(false)
  const [selectedRepositoryId, setSelectedRepositoryId] = useState<number | null>(null)
  const [searchInput, setSearchInput] = useState('')
  const [q, setQ] = useState('')
  const [status, setStatus] = useState<RepositoryStatus>()
  const [channel, setChannel] = useState<Channel>()
  const [paused, setPaused] = useState<boolean>()
  const [page, setPage] = useState(0)
  const [asOf, setAsOf] = useState<string>()
  const [refreshVersion, setRefreshVersion] = useState(0)
  const configuration = useGithubDeepDiscoveryConfiguration(visible)
  const progress = useGithubDeepDiscoveryProgress(visible)
  const rangeEnd = progress.data?.observedAt
  const from = rangeEnd ? new Date(Date.parse(rangeEnd) - days * 86_400_000).toISOString() : undefined
  const summary = useGithubDeepDiscoverySummary(from, rangeEnd, visible)
  const repositoryQuery: GithubDeepDiscoveryRepositoryQuery = { page, size: PAGE_SIZE, asOf, q: q || undefined, status, channel, paused }
  const repositories = useGithubDeepDiscoveryRepositories(repositoryQuery, visible && tab === 'repositories', refreshVersion)
  const heldAsOf = asOf ?? repositories.data?.asOf
  const head = useGithubDeepDiscoveryRepositories({ page: 0, size: 1, q: q || undefined, status, channel, paused }, visible && tab === 'repositories' && Boolean(heldAsOf), 0, 60_000)
  const firstHeadTime = head.data?.items?.[0]?.firstDiscoveredAt
  const newResults = Boolean(firstHeadTime && heldAsOf && Date.parse(firstHeadTime) > Date.parse(heldAsOf))
  const date = (value?: string | null) => formatLocalDateTime(value, i18n.language)

  useEffect(() => {
    if (!visible) {
      void queryClient.cancelQueries({ queryKey: DEEP_DISCOVERY_QUERY_KEY })
    }
  }, [queryClient, visible])

  useEffect(() => {
    if (tab !== 'repositories') {
      void queryClient.cancelQueries({ queryKey: [...DEEP_DISCOVERY_QUERY_KEY, 'repositories'] })
    }
  }, [queryClient, tab])

  const resetList = () => { setPage(0); setAsOf(undefined) }
  const refreshList = () => { resetList(); setRefreshVersion((current) => current + 1) }
  const applySearch = (event: FormEvent) => { event.preventDefault(); setQ(searchInput.trim()); resetList() }
  const changePage = (next: number) => { setAsOf(heldAsOf); setPage(next) }
  const recent = summary.data?.recent
  const current = summary.data?.current
  const state = summary.data?.state ?? (configuration.data?.enabled ? undefined : 'DISABLED')

  return <div className="github-sync-surface space-y-5 animate-fade-up">
    <div className="sync-hero flex flex-wrap items-start justify-between gap-4">
      <div className="flex items-start gap-3"><Compass className="mt-1 h-6 w-6 shrink-0" style={{ color: 'var(--sync-teal)' }} aria-hidden="true" /><div>
        <DashboardPageHeader title={t('deepDiscovery.title')} subtitle={t('deepDiscovery.subtitle')} />
        <span className="mt-2 inline-flex rounded border px-2 py-0.5 text-xs text-muted-foreground">@github</span>
      </div></div>
      <div className="flex items-center gap-2">
        <span className="sync-state" data-tone={tone(state)}>{t(`deepDiscovery.state.${state}`, { defaultValue: state ?? '—' })}</span>
        <Button size="sm" variant="outline" onClick={() => setConfigOpen(true)} disabled={!configuration.data}><Settings2 className="h-4 w-4" />{t('deepDiscovery.configure')}</Button>
      </div>
    </div>
    {configuration.error ? <p role="alert" className="text-sm text-destructive">{t('deepDiscovery.configLoadFailed')}: {configuration.error.message}</p> : null}
    {!configuration.data && configuration.isLoading ? <p className="text-sm text-muted-foreground">{t('deepDiscovery.loading')}</p> : null}
    {configuration.data && !configuration.data.enabled ? <div className="rounded border border-amber-500/40 bg-amber-500/5 p-4 text-sm">
      <p className="font-semibold" style={{ color: 'var(--sync-amber)' }}>{t('deepDiscovery.pausedTitle')}</p>
      <p className="mt-1 text-muted-foreground">{t('deepDiscovery.pausedHelp')}</p>
    </div> : null}
    <Card className="sync-section overflow-hidden">
      <div className="flex flex-wrap items-center justify-between gap-2 border-b px-4 py-3 text-sm">
        <div className="flex flex-wrap gap-x-4 gap-y-1"><span>{t('deepDiscovery.budget')}: <strong>{t(`deepDiscovery.budgets.${configuration.data?.budgetProfile}`, { defaultValue: '—' })}</strong></span><span>{t('deepDiscovery.worker')}: <strong>{t(`deepDiscovery.workerState.${summary.data?.worker?.state}`, { defaultValue: summary.data?.worker?.state ?? '—' })}</strong></span></div>
        <label className="flex items-center gap-2">{t('deepDiscovery.period')}<select className="rounded border bg-background px-2 py-1" value={days} onChange={(event) => setDays(Number(event.target.value))}>
          <option value={7}>{t('deepDiscovery.lastDays', { count: 7 })}</option><option value={30}>{t('deepDiscovery.lastDays', { count: 30 })}</option><option value={90}>{t('deepDiscovery.lastDays', { count: 90 })}</option>
        </select></label>
      </div>
      <QueryMessage loading={summary.isLoading && !summary.data} error={summary.error} />
      <div className="grid divide-y sm:grid-cols-2 sm:divide-y-0 lg:grid-cols-4">
        <Metric label={t('deepDiscovery.newCandidates')} value={recent?.newCandidateRepositories} note={t('deepDiscovery.recentLabel')} />
        <Metric label={t('deepDiscovery.validRepositories')} value={recent?.firstValidRepositories} note={t('deepDiscovery.recentLabel')} />
        <Metric label={t('deepDiscovery.newSkills')} value={recent?.newSkills} note={t('deepDiscovery.notPublished')} />
        <Metric label={t('deepDiscovery.updatedVersions')} value={recent?.updatedVersions} note={t('deepDiscovery.recentLabel')} />
      </div>
      <div className="grid border-t bg-muted/20 sm:grid-cols-2">
        <Metric label={t('deepDiscovery.pendingChecks')} value={current?.pendingRepositoryChecks} note={t('deepDiscovery.currentLabel')} />
        <Metric label={t('deepDiscovery.pendingReviews')} value={current?.pendingReviewVersions} note={t('deepDiscovery.currentLabel')} />
      </div>
      {recent && !recent.complete ? <p className="border-t px-4 py-2 text-xs text-muted-foreground">{t('deepDiscovery.incompleteHistory', { date: date(recent.availableSince) })}</p> : null}
      {summary.data?.observedAt ? <p className="border-t px-4 py-2 text-xs text-muted-foreground">{t('deepDiscovery.observedAt')}: {date(summary.data.observedAt)}</p> : null}
    </Card>
    <Tabs value={tab} onValueChange={setTab} className="space-y-4">
      <TabsList className="sync-tabs flex w-full gap-6 overflow-x-auto"><TabsTrigger value="progress">{t('deepDiscovery.progressTab')}</TabsTrigger><TabsTrigger value="repositories">{t('deepDiscovery.repositoriesTab')}</TabsTrigger></TabsList>
      <TabsContent value="progress" className="space-y-4">
        <Card className="sync-section overflow-hidden">
          <div className="sync-section-header"><h2 className="font-semibold">{t('deepDiscovery.channels')}</h2><p className="text-xs text-muted-foreground">{t('deepDiscovery.noPercentage')}</p></div>
          <QueryMessage loading={progress.isLoading && !progress.data} error={progress.error} empty={progress.data?.channels?.length === 0} />
          {progress.data?.channels?.length ? <Table className="min-w-[640px]"><TableHeader><TableRow>
            <TableHead>{t('deepDiscovery.channelHeading')}</TableHead><TableHead>{t('deepDiscovery.completedShards')}</TableHead><TableHead>{t('deepDiscovery.pendingShards')}</TableHead><TableHead>{t('deepDiscovery.gapShards')}</TableHead><TableHead>{t('deepDiscovery.lastProgress')}</TableHead><TableHead>{t('deepDiscovery.statusHeading')}</TableHead>
          </TableRow></TableHeader><TableBody>{progress.data.channels.map((item) => <TableRow key={item.channel}>
            <TableCell className="font-medium">{t(`deepDiscovery.channel.${item.channel}`, { defaultValue: item.channel ?? '—' })}</TableCell>
            <TableCell>{item.completedShards ?? '—'}</TableCell><TableCell>{item.pendingShards ?? '—'}</TableCell><TableCell>{item.gapShards ?? '—'}</TableCell>
            <TableCell>{date(item.lastProgressAt)}</TableCell><TableCell><span className="sync-state" data-tone={tone(item.state)}>{t(`deepDiscovery.state.${item.state}`, { defaultValue: item.state ?? '—' })}</span></TableCell>
          </TableRow>)}</TableBody></Table> : null}
        </Card>
        {summary.data?.waits?.length ? <Card className="sync-section overflow-hidden"><div className="sync-section-header"><h2 className="font-semibold">{t('deepDiscovery.waiting')}</h2></div>{summary.data.waits.map((wait, index) => <WaitNotice key={`${wait.reason}:${index}`} wait={wait} onConfigure={() => setConfigOpen(true)} />)}</Card> : null}
        {progress.data?.quotas?.length ? <Card className="sync-section overflow-hidden"><div className="sync-section-header"><h2 className="font-semibold">{t('deepDiscovery.quotaObservations')}</h2><p className="text-xs text-muted-foreground">{t('deepDiscovery.quotaHelp')}</p></div><Table className="min-w-[560px]"><TableHeader><TableRow><TableHead>{t('deepDiscovery.resource')}</TableHead><TableHead>{t('deepDiscovery.statusHeading')}</TableHead><TableHead>{t('deepDiscovery.remaining')}</TableHead><TableHead>{t('deepDiscovery.observedAt')}</TableHead></TableRow></TableHeader><TableBody>{progress.data.quotas.map((quota, index) => <TableRow key={`${quota.resource}:${quota.subjectKey}:${index}`}><TableCell>{quota.resource ?? '—'}</TableCell><TableCell>{t(`deepDiscovery.quotaState.${quota.state}`, { defaultValue: quota.state ?? '—' })}</TableCell><TableCell>{quota.remaining ?? '—'}</TableCell><TableCell>{date(quota.observedAt)}</TableCell></TableRow>)}</TableBody></Table>{progress.data.quotaObservationsTruncated ? <p className="p-3 text-xs text-muted-foreground">{t('deepDiscovery.quotaTruncated')}</p> : null}</Card> : null}
        <Card className="sync-section overflow-hidden"><div className="sync-section-header"><h2 className="font-semibold">{t('deepDiscovery.recentRuns')}</h2></div>
          {summary.data?.recentRunIds?.length ? <div className="divide-y">{summary.data.recentRunIds.map((runId) => <div key={runId} className="flex flex-wrap items-center justify-between gap-2 px-4 py-3 text-sm"><span className="font-mono">{runId.slice(0, 8)}</span><Link to="/dashboard/github-sync" search={{ runId }} className="text-primary hover:underline">{t('deepDiscovery.viewRun')} <ArrowRight className="inline h-4 w-4" /></Link></div>)}</div> : <p className="p-4 text-sm text-muted-foreground">{t('deepDiscovery.noRecentRuns')}</p>}
        </Card>
      </TabsContent>
      <TabsContent value="repositories" className="space-y-4">
        <Card className="sync-section overflow-hidden">
          <div className="space-y-3 p-4"><div className="flex flex-wrap items-center justify-between gap-2"><h2 className="font-semibold">{t('deepDiscovery.repositoriesTab')}</h2><Button size="sm" variant="outline" onClick={refreshList}><RefreshCw className="h-4 w-4" />{t('deepDiscovery.refresh')}</Button></div>
            <form onSubmit={applySearch} className="flex flex-wrap gap-2"><Input className="min-w-44 flex-1" value={searchInput} maxLength={200} onChange={(event) => setSearchInput(event.target.value)} aria-label={t('deepDiscovery.searchLabel')} placeholder={t('deepDiscovery.searchPlaceholder')} /><Button size="sm" type="submit">{t('deepDiscovery.search')}</Button></form>
            <div className="flex flex-wrap gap-2">
              <select aria-label={t('deepDiscovery.filterStatus')} value={status ?? ''} onChange={(event) => { setStatus((event.target.value || undefined) as RepositoryStatus); resetList() }} className="rounded border bg-background px-2 py-1 text-sm"><option value="">{t('deepDiscovery.allStatuses')}</option>{(['PENDING', 'VALIDATING', 'VALID', 'NO_VALID_SKILL', 'VALIDATION_FAILED', 'UNREACHABLE'] as const).map((value) => <option key={value} value={value}>{t(`deepDiscovery.status.${value}`)}</option>)}</select>
              <select aria-label={t('deepDiscovery.filterChannel')} value={channel ?? ''} onChange={(event) => { setChannel((event.target.value || undefined) as Channel); resetList() }} className="rounded border bg-background px-2 py-1 text-sm"><option value="">{t('deepDiscovery.allChannels')}</option>{(['REPOSITORY_METADATA', 'README', 'OWNER_EXPANSION'] as const).map((value) => <option key={value} value={value}>{t(`deepDiscovery.channel.${value}`)}</option>)}</select>
              <select aria-label={t('deepDiscovery.filterPaused')} value={paused === undefined ? '' : String(paused)} onChange={(event) => { setPaused(event.target.value === '' ? undefined : event.target.value === 'true'); resetList() }} className="rounded border bg-background px-2 py-1 text-sm"><option value="">{t('deepDiscovery.allPaused')}</option><option value="true">{t('deepDiscovery.onlyPaused')}</option><option value="false">{t('deepDiscovery.onlyActive')}</option></select>
            </div>
            {newResults ? <div className="flex items-center justify-between gap-2 rounded border border-primary/30 bg-primary/5 p-2 text-sm"><span>{t('deepDiscovery.newResultsHint')}</span><Button size="sm" variant="ghost" onClick={refreshList}>{t('deepDiscovery.refresh')}</Button></div> : null}
          </div>
          <QueryMessage loading={repositories.isLoading} error={repositories.error} empty={!repositories.isLoading && !repositories.error && repositories.data?.items?.length === 0} />
          {repositories.data?.items?.length ? <Table className="min-w-[760px]"><TableHeader><TableRow><TableHead>{t('deepDiscovery.repositoryHeading')}</TableHead><TableHead>{t('deepDiscovery.statusHeading')}</TableHead><TableHead>{t('deepDiscovery.channelHeading')}</TableHead><TableHead>{t('deepDiscovery.firstDiscovered')}</TableHead><TableHead>{t('deepDiscovery.lastDiscovered')}</TableHead><TableHead>{t('deepDiscovery.actionHeading')}</TableHead></TableRow></TableHeader><TableBody>{repositories.data.items.map((repository) => <TableRow key={repository.repositoryId}>
            <TableCell className="break-all font-medium">{repository.owner}/{repository.name}{repository.sourceId ? <span className="ml-2 text-xs text-muted-foreground">{t('deepDiscovery.registeredSource')}</span> : null}</TableCell>
            <TableCell><span className="sync-state" data-tone={tone(repository.status)}>{t(`deepDiscovery.status.${repository.status}`, { defaultValue: repository.status ?? '—' })}</span>{repository.paused ? <p className="mt-1 text-xs text-muted-foreground">{t('deepDiscovery.paused')}</p> : null}</TableCell>
            <TableCell>{(repository.channels ?? []).map((value) => t(`deepDiscovery.channel.${value}`)).join('、') || '—'}</TableCell><TableCell>{date(repository.firstDiscoveredAt)}</TableCell><TableCell>{date(repository.lastDiscoveredAt)}</TableCell>
            <TableCell><Button size="sm" variant="outline" disabled={!repository.repositoryId} onClick={() => setSelectedRepositoryId(repository.repositoryId ?? null)}>{t('deepDiscovery.viewDetail')}</Button></TableCell>
          </TableRow>)}</TableBody></Table> : null}
        </Card>
        {repositories.data ? <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground"><span>{t('deepDiscovery.resultCount', { count: repositories.data.total ?? 0 })} · {t('deepDiscovery.asOf')}: {date(heldAsOf)}</span><GithubDeepDiscoveryPagination page={page} total={repositories.data.total ?? 0} size={PAGE_SIZE} onPageChange={changePage} /></div> : null}
        <p className="text-xs text-muted-foreground">{t('deepDiscovery.asOfHelp')}</p>
      </TabsContent>
    </Tabs>
    {configOpen && configuration.data ? <GithubDeepDiscoveryConfig key={configuration.data.version} configuration={configuration.data} onClose={() => setConfigOpen(false)} /> : null}
    {selectedRepositoryId !== null ? <GithubDeepDiscoveryRepositoryDrawer key={selectedRepositoryId} repositoryId={selectedRepositoryId} visible={visible} onClose={() => setSelectedRepositoryId(null)} /> : null}
  </div>
}
