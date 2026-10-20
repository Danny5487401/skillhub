import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { describe, expect, it, vi } from 'vitest'
import { GithubSyncConflictBinder } from './github-sync-conflict-binder'

vi.mock('react-i18next', async (importOriginal) => ({
  ...await importOriginal<typeof import('react-i18next')>(),
  useTranslation: () => ({ t: (key: string) => key }),
}))

describe('GithubSyncConflictBinder', () => {
  const render = (ingestionErrorCode: string) => renderToStaticMarkup(
    createElement(QueryClientProvider, { client: new QueryClient() },
      createElement(GithubSyncConflictBinder, {
        path: {
          skillPath: 'agents/demo', ingestionId: '11111111-1111-1111-1111-111111111111',
          ingestionErrorCode, targetSkillSlug: 'demo',
        },
        namespaceSlug: 'team-a',
      })),
  )

  it('offers resolution only for a slug conflict with a target slug', () => {
    expect(render('SLUG_CONFLICT')).toContain('githubSync.resolveConflict')
    expect(render('PATH_MOVE_AMBIGUOUS')).toContain('githubSync.resolvePathMove')
    expect(render('PUBLISH_POLICY_BLOCKED')).not.toContain('githubSync.resolveConflict')
  })
})
