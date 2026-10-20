/** @vitest-environment jsdom */

import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import type { GithubSyncReviewSource } from '@/api/types'
import { GithubSyncReviewDiff } from './github-sync-review-source'

const queries = vi.hoisted(() => ({ enabled: [] as boolean[], keys: [] as unknown[][], staleTimes: [] as (number | undefined)[] }))

vi.mock('@tanstack/react-query', () => ({
  useQuery: ({ queryKey, enabled, staleTime }: { queryKey: unknown[]; enabled: boolean; staleTime?: number }) => {
    queries.enabled.push(enabled)
    queries.keys.push(queryKey)
    queries.staleTimes.push(staleTime)
    return {
      data: enabled ? (queryKey[2] === 'sync-base-file-preview' ? 'old instruction' : 'new instruction') : undefined,
      isLoading: false,
      isError: false,
    }
  },
}))
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }))
vi.mock('@/api/client', () => ({
  buildApiUrl: (path: string) => path,
  fetchText: vi.fn(),
  WEB_API_PREFIX: '/api/web',
}))
vi.mock('./review-file-diff', () => ({
  default: ({ oldText, newText }: { oldText: string; newText: string }) => <div>{oldText} → {newText}</div>,
}))

const source: GithubSyncReviewSource = {
  ingestionId: 'ingestion', runId: 'run', repositoryId: 1, ownerLogin: 'owner', repositoryName: 'repo',
  commitSha: 'a'.repeat(40), skillPath: 'skills/example', contentSha256: 'content', syncRevision: 2,
  ingestionStatus: 'PENDING', receivedAt: '2026-10-13T00:00:00Z', baseVersionId: 9,
  evidence: { status: 'RECOGNIZED', documents: [] },
  fileChanges: [
    { path: 'SKILL.md', changeType: 'MODIFIED', oldSize: 15, newSize: 15, oldSha256: 'old', newSha256: 'new' },
    { path: 'removed.md', changeType: 'REMOVED', oldSize: 15, oldSha256: 'removed' },
    { path: 'image.png', changeType: 'ADDED', newSize: 30, newSha256: 'binary' },
    { path: 'large.md', changeType: 'ADDED', newSize: 256 * 1024 + 1, newSha256: 'large' },
    { path: 'huge-removed.md', changeType: 'REMOVED', oldSize: 1024 * 1024 + 1, oldSha256: 'huge' },
  ],
}

describe('GithubSyncReviewDiff', () => {
  afterEach(() => {
    cleanup()
    queries.enabled = []
    queries.keys = []
    queries.staleTimes = []
  })

  it('selects one file at a time and degrades removed, binary and oversize previews safely', async () => {
    render(<GithubSyncReviewDiff source={source} reviewId={42} />)
    await waitFor(() => expect(screen.getByText('old instruction → new instruction')).not.toBeNull())
    expect(queries.enabled.slice(-2)).toEqual([true, true])
    expect(queries.keys.at(-1)).toEqual(['reviews', 42, 'file', 'SKILL.md'])
    expect(queries.staleTimes.slice(-2)).toEqual([300000, 300000])

    const select = screen.getByRole('combobox', { name: 'review.githubSelectFile' })
    fireEvent.change(select, { target: { value: 'removed.md' } })
    expect(screen.getByText('old instruction')).not.toBeNull()
    expect(queries.enabled.slice(-2)).toEqual([true, false])

    fireEvent.change(select, { target: { value: 'image.png' } })
    expect(screen.getByText('filePreview.binaryFile')).not.toBeNull()
    expect(queries.enabled.slice(-2)).toEqual([false, false])
    expect(screen.getByRole('link', { name: 'filePreview.downloadFile' })).not.toBeNull()

    fireEvent.change(select, { target: { value: 'large.md' } })
    expect(screen.getByText('filePreview.tooLarge')).not.toBeNull()
    expect(queries.enabled.slice(-2)).toEqual([false, false])

    fireEvent.change(select, { target: { value: 'huge-removed.md' } })
    expect(screen.getByText('review.githubBaseFileUnavailable')).not.toBeNull()
    expect(screen.queryByRole('link', { name: 'filePreview.downloadFile' })).toBeNull()
    expect(queries.enabled.slice(-2)).toEqual([false, false])
  })
})
