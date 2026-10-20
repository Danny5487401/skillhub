import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it, vi } from 'vitest'
import { GithubSyncRunList } from './github-sync-run-list'

vi.mock('react-i18next', async (importOriginal) => ({
  ...await importOriginal<typeof import('react-i18next')>(),
  useTranslation: () => ({ t: (key: string) => key, i18n: { language: 'zh-CN' } }),
}))

describe('GithubSyncRunList', () => {
  it('shows run status and package stages', () => {
    const html = renderToStaticMarkup(createElement(GithubSyncRunList, {
      data: { items: [{ id: '12345678-aaaa-bbbb-cccc-123456789abc', kind: 'REPOSITORY_SYNC',
        status: 'RETRY_WAIT', lastErrorCode: 'GITHUB_RATE_LIMITED', taskCount: 2, receivedPackages: 3,
        processingPackages: 4, pendingReviewPackages: 5, blockedPackages: 7, failedPackages: 6,
        createdAt: '2026-09-29T02:00:00Z' }], total: 1, size: 20 },
      loading: false, error: null, page: 0, onPageChange: () => {},
    }))

    expect(html).toContain('12345678')
    expect(html).toContain('githubSync.codes.REPOSITORY_SYNC')
    expect(html).toContain('githubSync.codes.RETRY_WAIT')
    expect(html).toContain('githubSync.failureReason')
    expect(html).toContain('GITHUB_RATE_LIMITED')
    expect(html).toContain('githubSync.pendingReview')
    expect(html).toContain('githubSync.blocked')
    expect(html).toContain('githubSync.packageStages')
    expect(html).toContain('<strong>5</strong>')
    expect(html).toContain('<strong>7</strong>')
  })

  it('shows empty and error feedback', () => {
    const render = (error: Error | null) => renderToStaticMarkup(createElement(GithubSyncRunList, {
      data: { items: [], total: 0, size: 20 }, loading: false, error,
      page: 0, onPageChange: () => {},
    }))

    expect(render(null)).toContain('githubSync.empty')
    expect(render(new Error('unavailable'))).toContain('unavailable')
    expect(render(new Error('unavailable'))).not.toContain('githubSync.empty')
  })

  it('shows the real target summary without presenting one task as all targets', () => {
    const html = renderToStaticMarkup(createElement(GithubSyncRunList, {
      data: { items: [
        { id: '12345678-aaaa-bbbb-cccc-123456789abc', kind: 'DISCOVERY', targetCount: 1,
          target: { type: 'REPOSITORY', value: 'owner/repo' }, status: 'QUEUED' },
        { id: '22345678-aaaa-bbbb-cccc-123456789abc', kind: 'DISCOVERY', targetCount: 3,
          target: { type: 'REPOSITORY', value: 'only-the-first' }, status: 'QUEUED' },
      ], total: 2, size: 20 }, loading: false, error: null, page: 0, onPageChange: () => {},
    }))
    expect(html).toContain('owner/repo')
    expect(html).toContain('githubSync.runMultipleTargets')
    expect(html).not.toContain('only-the-first')
  })
})
