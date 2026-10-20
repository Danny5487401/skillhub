import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it, vi } from 'vitest'
import { GithubSkillSourceCard } from './github-skill-source'

vi.mock('react-i18next', () => ({
  useTranslation: () => ({ t: (key: string) => key }),
}))

describe('GithubSkillSourceCard', () => {
  it('links the published commit and both license scopes to fixed GitHub paths', () => {
    const commitSha = 'a'.repeat(40)
    const html = renderToStaticMarkup(createElement(GithubSkillSourceCard, {
      version: '1.2.0',
      source: {
        ownerLogin: 'upstream',
        repositoryName: 'skills',
        skillPath: 'agents/demo',
        commitSha,
        syncRevision: 2,
        licenseStatus: 'PRESENT_UNCLASSIFIED',
        licenseDocuments: [
          { scope: 'REPOSITORY_ROOT', path: 'LICENSE', bundled: true },
          { scope: 'SKILL_ROOT', path: 'NOTICE.md', bundled: false },
        ],
      },
    }))

    expect(html).toContain(`/commit/${commitSha}`)
    expect(html).toContain(`/blob/${commitSha}/LICENSE`)
    expect(html).toContain(`/blob/${commitSha}/agents/demo/NOTICE.md`)
    expect(html).toContain('review.githubRevisionValue')
    expect(html).not.toContain('ingestionStatus')
  })
})
