// @vitest-environment jsdom

import type { MouseEvent, MouseEventHandler, ReactNode } from 'react'
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

afterEach(cleanup)

vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key, i18n: { language: 'zh' } }) }))
vi.mock('@tanstack/react-router', () => ({
  Link: ({ children, onClickCapture }: { children: ReactNode; onClickCapture?: MouseEventHandler<HTMLAnchorElement> }) =>
    <a href="/dashboard/reviews/1" onClickCapture={onClickCapture} onClick={(event) => event.preventDefault()}>{children}</a>,
}))
vi.mock('@/shared/ui/dialog', () => ({
  Dialog: ({ children, onOpenChange }: { children: ReactNode; onOpenChange: (open: boolean) => void }) =>
    <div onClick={(event: MouseEvent<HTMLDivElement>) => {
      if ((event.target as HTMLElement).closest('a')) onOpenChange(false)
    }}>{children}<button type="button" onClick={() => onOpenChange(false)}>close dialog</button></div>,
  DialogContent: ({ children }: { children: ReactNode }) => <div>{children}</div>,
  DialogHeader: ({ children }: { children: ReactNode }) => <div>{children}</div>,
  DialogTitle: ({ children }: { children: ReactNode }) => <h2>{children}</h2>,
  DialogDescription: ({ children }: { children: ReactNode }) => <p>{children}</p>,
}))
vi.mock('./use-github-sync-admin', () => ({
  useGithubSyncRepository: () => ({ data: { repository: { repositoryId: 21, owner: 'fixture', name: 'repo', state: 'REGISTERED' } }, isLoading: false, error: null }),
  useGithubSyncRepositoryOrigins: () => ({ data: { items: [], total: 0 }, isLoading: false, error: null }),
  useGithubSyncRepositoryPaths: () => ({ data: { items: [{ skillPath: 'SKILL.md', outcome: 'ACCEPTED', reviewId: 1 }], total: 1 }, isLoading: false, error: null }),
}))
vi.mock('./github-sync-source-detail', () => ({ GithubSyncSourceDetail: () => null }))

import { RepositoryDetail } from './github-sync-repositories'

describe('repository review navigation', () => {
  it('does not let dialog close overwrite review navigation', () => {
    const onClose = vi.fn()
    render(<RepositoryDetail repositoryId={21} visible onClose={onClose} onRun={vi.fn()} />)
    fireEvent.click(screen.getByText('deepDiscovery.viewReview'))
    expect(onClose).not.toHaveBeenCalled()
  })

  it('still closes from a normal dialog close action', () => {
    const onClose = vi.fn()
    render(<RepositoryDetail repositoryId={21} visible onClose={onClose} onRun={vi.fn()} />)
    fireEvent.click(screen.getByText('close dialog'))
    expect(onClose).toHaveBeenCalledOnce()
  })
})
