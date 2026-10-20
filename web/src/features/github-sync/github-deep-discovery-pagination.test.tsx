import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it, vi } from 'vitest'

vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string, options?: { count?: number }) => `${key}:${options?.count ?? ''}` }) }))
vi.mock('@/shared/components/pagination', () => ({
  Pagination: ({ totalPages }: { totalPages: number }) => createElement('span', { 'data-pages': totalPages }),
}))

import { deepDiscoveryPageCount, GithubDeepDiscoveryPagination } from './github-deep-discovery-pagination'

describe('deep discovery pagination boundary', () => {
  it('never offers a page rejected by page <= 1000 or offset <= 100000', () => {
    expect(deepDiscoveryPageCount(20_021, 20)).toBe(1001)
    expect(deepDiscoveryPageCount(200_000, 100)).toBe(1001)
    expect(deepDiscoveryPageCount(100, 20)).toBe(5)
  })

  it('keeps the true result count out of the page limit and explains the boundary', () => {
    const markup = renderToStaticMarkup(createElement(GithubDeepDiscoveryPagination, {
      page: 0, total: 20_021, size: 20, onPageChange: () => {},
    }))
    expect(markup).toContain('data-pages="1001"')
    expect(markup).toContain('deepDiscovery.paginationLimit:20020')
  })
})
