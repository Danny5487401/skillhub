import { beforeEach, describe, expect, it, vi } from 'vitest'
import { renderToStaticMarkup } from 'react-dom/server'
import { createElement } from 'react'

vi.mock('@tanstack/react-router', () => ({
  Link: ({ children, to }: { children: unknown; to: string }) => createElement('a', { href: to }, children as string),
  useNavigate: () => vi.fn(),
  useParams: () => ({ slug: 'test-ns' }),
  useSearch: () => ({}),
}))

vi.mock('@/features/review/github-sync-review-queue', () => ({
  GithubSyncReviewQueue: () => null,
}))

vi.mock('react-i18next', async () => {
  const actual = await vi.importActual<typeof import('react-i18next')>('react-i18next')
  return {
    ...actual,
    useTranslation: () => ({
      t: (key: string) => key,
      i18n: { language: 'en' },
    }),
  }
})

vi.mock('@/shared/lib/date-time', () => ({
  formatLocalDateTime: (v: string) => v,
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

vi.mock('@/shared/ui/tabs', () => ({
  Tabs: ({ children }: { children: unknown }) => children,
  TabsContent: ({ children }: { children: unknown }) => children,
  TabsList: ({ children }: { children: unknown }) => children,
  TabsTrigger: ({ children }: { children: unknown }) => children,
}))

const paginationProps: Array<{ page: number; totalPages: number; onPageChange: (page: number) => void }> = []
vi.mock('@/shared/components/pagination', () => ({
  Pagination: (props: { page: number; totalPages: number; onPageChange: (page: number) => void }) => {
    paginationProps.push(props)
    return null
  },
}))

const useNamespaceDetailMock = vi.fn()
vi.mock('@/shared/hooks/use-namespace-queries', () => ({
  useNamespaceDetail: (...args: unknown[]) => useNamespaceDetailMock(...args),
}))

const useReviewListMock = vi.fn()
vi.mock('@/features/review/use-review-list', () => ({
  useReviewList: (...args: unknown[]) => useReviewListMock(...args),
}))

vi.mock('@/shared/components/dashboard-page-header', () => ({
  DashboardPageHeader: () => null,
}))

vi.mock('@/features/namespace/namespace-header', () => ({
  NamespaceHeader: () => null,
}))

vi.mock('@/features/namespace/namespace-management-nav', () => ({ NamespaceManagementBack: () => createElement('a', { href: '/dashboard/namespaces' }, 'back'), NamespaceManagementNav: ({ slug, active }: { slug: string; active: string }) => createElement('nav', { 'data-slug': slug, 'data-active': active }) }))

import { NamespaceReviewsPage } from './namespace-reviews'

describe('NamespaceReviewsPage', () => {
  function createReviewItem(id: number) {
    return {
      id,
      namespace: 'demo-ns',
      skillSlug: `skill-${id}`,
      version: '1.0.0',
      submittedBy: 'user-1',
      submittedByName: 'User 1',
      submittedAt: '2026-04-01T12:00:00Z',
      reviewedBy: null,
      reviewedByName: null,
      reviewedAt: null,
      reviewComment: null,
    }
  }

  beforeEach(() => {
    paginationProps.length = 0
    useNamespaceDetailMock.mockReset()
    useReviewListMock.mockReset()

    useNamespaceDetailMock.mockReturnValue({
      data: {
        id: 100,
        slug: 'test-ns',
        displayName: 'Test Namespace',
        type: 'CUSTOM',
        status: 'ACTIVE',
      },
      isLoading: false,
    })

    useReviewListMock.mockImplementation((status: string, _namespaceId: unknown, page: number, _size: number, _sortDirection: string, enabled: boolean) => {
      if (!enabled || status !== 'PENDING') {
        return { data: null, isLoading: false }
      }
      return {
        data: {
          items: [createReviewItem(1)],
          totalElements: 11,
          totalPages: 2,
          page,
          size: 10,
          total: 11,
        },
        isLoading: false,
      }
    })
  })

  it('exports a named component function', () => {
    expect(typeof NamespaceReviewsPage).toBe('function')
  })

  it('uses a status filter instead of nested review tabs', () => {
    const html = renderToStaticMarkup(createElement(NamespaceReviewsPage))
    expect(html).toContain('nsReviews.statusLabel')
    expect(html).toContain('nsReviews.tabPending')
    expect(html).toContain('nsReviews.sortLabel')
    expect(html).not.toContain('role="tab"')
  })

  it('renders pagination for namespace review list when totalPages > 1', () => {
    const html = renderToStaticMarkup(createElement(NamespaceReviewsPage))

    expect(html).toContain('data-active="reviews"')
    expect(html).toContain('data-slug="test-ns"')
    expect(html).toContain('nsReviews.pageSummary')
    expect(html).toContain('/dashboard/namespaces/test-ns/reviews/1')
    expect(html).toContain('nsReviews.openReview')
    expect(paginationProps).toHaveLength(1)
    expect(paginationProps[0]?.page).toBe(0)
    expect(paginationProps[0]?.totalPages).toBe(2)
  })

  it('does not render pagination when there is only one page', () => {
    useReviewListMock.mockImplementation((status: string, _namespaceId: unknown, page: number, _size: number, _sortDirection: string, enabled: boolean) => {
      if (!enabled || status !== 'PENDING') {
        return { data: null, isLoading: false }
      }
      return {
        data: {
          items: [createReviewItem(2)],
          totalElements: 1,
          totalPages: 1,
          page,
          size: 10,
          total: 1,
        },
        isLoading: false,
      }
    })

    renderToStaticMarkup(createElement(NamespaceReviewsPage))

    expect(paginationProps).toHaveLength(0)
  })

  it('does not enable review queries before namespace detail resolves', () => {
    useNamespaceDetailMock.mockReturnValue({
      data: undefined,
      isLoading: true,
    })

    renderToStaticMarkup(createElement(NamespaceReviewsPage))

    expect(useReviewListMock).toHaveBeenCalled()
    for (const call of useReviewListMock.mock.calls) {
      expect(call[5]).toBe(false)
    }
  })

  it('lists avatar-only tasks in the same queue with their existing review detail link', () => {
    useReviewListMock.mockReturnValue({ data: { items: [{ ...createReviewItem(9), subjectType: 'SKILL_METADATA', version: null, avatarRemoval: true }], totalElements: 1, totalPages: 1 }, isLoading: false })
    const html = renderToStaticMarkup(createElement(NamespaceReviewsPage))
    expect(html).toContain('review.avatarRemoval')
    expect(html).toContain('/dashboard/namespaces/test-ns/reviews/9')
    expect(html).not.toContain('skillAvatar.reviewTitle')
  })

})
