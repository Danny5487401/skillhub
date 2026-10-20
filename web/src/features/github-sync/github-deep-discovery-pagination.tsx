import { useTranslation } from 'react-i18next'
import { Pagination } from '@/shared/components/pagination'

const MAX_PAGE = 1000
const MAX_OFFSET = 100_000

export function deepDiscoveryPageCount(total: number, size: number) {
  return Math.min(Math.ceil(total / size), MAX_PAGE + 1, Math.floor(MAX_OFFSET / size) + 1)
}

export function GithubDeepDiscoveryPagination({ page, total, size, onPageChange, detail = false }: {
  page: number
  total: number
  size: number
  onPageChange: (page: number) => void
  detail?: boolean
}) {
  const { t } = useTranslation()
  const pages = deepDiscoveryPageCount(total, size)
  const limited = total > pages * size

  return <div>
    {pages > 1 ? <Pagination page={page} totalPages={pages} onPageChange={onPageChange} /> : null}
    {limited ? <p role="status" className="text-xs text-muted-foreground">
      {t(detail ? 'deepDiscovery.detailPaginationLimit' : 'deepDiscovery.paginationLimit', { count: pages * size })}
    </p> : null}
  </div>
}
