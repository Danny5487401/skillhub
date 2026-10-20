import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { beforeEach, describe, expect, it, vi } from 'vitest'

const queueQuery = vi.fn()
vi.mock('@/features/review/use-github-sync-review-queue', () => ({
  useGithubSyncReviewQueue: (...args: unknown[]) => queueQuery(...args),
}))
vi.mock('@tanstack/react-router', () => ({
  Link: ({ children, to }: { children: string; to: string }) => createElement('a', { href: to }, children),
}))
vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key, i18n: { language: 'zh' } }),
}))
vi.mock('@/shared/ui/card', () => ({
  Card: ({ children }: { children: unknown }) => children,
}))
vi.mock('@/shared/ui/select', () => ({
  Select: ({ children }: { children: unknown }) => children,
  SelectContent: ({ children }: { children: unknown }) => children,
  SelectItem: ({ children }: { children: unknown }) => children,
  SelectTrigger: ({ children }: { children: unknown }) => children,
  SelectValue: () => null,
}))
vi.mock('@/shared/components/pagination', () => ({ Pagination: () => null }))
vi.mock('@/shared/lib/date-time', () => ({ formatLocalDateTime: (value: string) => value }))

import { GithubSyncReviewQueue } from './github-sync-review-queue'

function item(id: number, runId: string, repositoryId: number, repositoryName: string) {
  return {
    review: { id, namespace: 'team-a', skillSlug: `skill-${id}`, status: 'PENDING',
      submittedAt: '2026-09-29T00:00:00Z' },
    ingestionId: `ingestion-${id}`, runId, repositoryId,
    ownerLogin: 'owner', repositoryName,
    skillPath: `skills/skill-${id}`, commitSha: 'a'.repeat(40),
  }
}

describe('GithubSyncReviewQueue', () => {
  beforeEach(() => queueQuery.mockReset())

  it('groups the current page by repository and run while keeping per-skill review links', () => {
    queueQuery.mockReturnValue({
      data: { items: [item(1, 'run-one', 10, 'repo-a'), item(2, 'run-one', 10, 'repo-a'),
        item(3, 'run-two', 20, 'repo-b')], total: 3, page: 0, size: 20 },
      isLoading: false, isError: false,
    })

    const html = renderToStaticMarkup(createElement(GithubSyncReviewQueue,
      { namespaceId: 42, namespaceSlug: 'team-a' }))

    expect(html.match(/owner\/repo-a/g)).toHaveLength(1)
    expect(html.match(/owner\/repo-b/g)).toHaveLength(1)
    expect(html).toContain('/dashboard/namespaces/team-a/reviews/1')
    expect(html).toContain('/dashboard/namespaces/team-a/reviews/2')
    expect(html).toContain('/dashboard/namespaces/team-a/reviews/3')
    expect(queueQuery).toHaveBeenCalledWith('PENDING', 42, 0, 'DESC', true)
  })

  it('shows the queue error without rendering review links', () => {
    queueQuery.mockReturnValue({ data: undefined, isLoading: false, isError: true })

    const html = renderToStaticMarkup(createElement(GithubSyncReviewQueue, {}))

    expect(html).toContain('reviews.syncError')
    expect(html).not.toContain('/dashboard/reviews/')
  })
})
