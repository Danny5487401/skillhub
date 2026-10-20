import { createElement } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { describe, expect, it, vi } from 'vitest'
import type { SkillSummary } from '@/api/types'
import { GithubCatalogSkillRow } from './github-skills'

vi.mock('react-i18next', async (importOriginal) => ({
  ...await importOriginal<typeof import('react-i18next')>(),
  useTranslation: () => ({ t: (key: string, options?: { count?: number }) => options?.count
    ? `${key}:${options.count}` : key }),
}))

const skill: SkillSummary = {
  id: 1,
  slug: 'example',
  displayName: 'Example',
  downloadCount: 0,
  starCount: 0,
  ratingCount: 0,
  namespace: 'github',
  updatedAt: '2026-10-12T00:00:00Z',
  canSubmitPromotion: false,
  headlineVersion: { id: 2, version: '0.0.0-g' + 'a'.repeat(40), status: 'PUBLISHED', syncRevision: 2 },
  publishedVersion: { id: 2, version: '0.0.0-g' + 'a'.repeat(40), status: 'PUBLISHED', syncRevision: 2 },
}

describe('GithubCatalogSkillRow', () => {
  it('shows the sync revision without exposing the long technical version in the market row', () => {
    const html = renderToStaticMarkup(createElement(GithubCatalogSkillRow, { skill, onOpen: () => {} }))
    expect(html).toContain('review.githubRevisionValue:2')
    expect(html).not.toContain(skill.publishedVersion?.version)
  })

  it('keeps the normal version label when there is no sync revision', () => {
    const normalVersion = { id: 3, version: '1.2.3', status: 'PUBLISHED' }
    const normalSkill = { ...skill, headlineVersion: normalVersion, publishedVersion: normalVersion }
    const html = renderToStaticMarkup(createElement(GithubCatalogSkillRow, { skill: normalSkill, onOpen: () => {} }))
    expect(html).toContain('v1.2.3')
  })
})
