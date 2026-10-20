import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it, vi } from 'vitest'
import { GithubSyncPathIdentity } from './github-sync-path-identity'

vi.mock('react-i18next', async (importOriginal) => ({
  ...await importOriginal<typeof import('react-i18next')>(),
  useTranslation: () => ({ t: (key: string) => key, i18n: { language: 'zh-CN' } }),
}))

describe('GithubSyncPathIdentity', () => {
  it('shows the bound identity and last full-scan presence', () => {
    const html = renderToStaticMarkup(createElement(GithubSyncPathIdentity, {
      path: {
        skillPath: 'agents/demo', skillId: 42,
        upstreamPresence: 'MISSING', presenceCommitSha: 'a'.repeat(40),
        missingSince: '2026-09-29T02:00:00Z',
      },
    }))

    expect(html).toContain('agents/demo')
    expect(html).toContain('githubSync.boundSkillId')
    expect(html).toContain('42')
    expect(html).toContain('githubSync.codes.MISSING')
    expect(html).toContain('aaaaaaa')
  })
})
