import { describe, expect, it } from 'vitest'
import { getVersionPresentation } from './version-presentation'

describe('historical version presentation', () => {
  it('uses the explicit version override before original package metadata', () => {
    expect(getVersionPresentation(JSON.stringify({ name: 'slug', description: '旧版摘要', displayNameOverride: ' 网页名 ', frontmatter: { metadata: { 'display-name': '包内名' } } })))
      .toEqual({ displayName: '网页名', summary: '旧版摘要' })
  })
  it('keeps old package-only snapshots readable and falls back for unsupported metadata', () => {
    expect(getVersionPresentation(JSON.stringify({ name: 'slug', frontmatter: { metadata: { 'display-name': '包内名' } } })).displayName).toBe('包内名')
    for (const value of [123, '长'.repeat(201), null, '   ']) {
      expect(getVersionPresentation(JSON.stringify({ name: 'slug', frontmatter: { metadata: { 'display-name': value } } })).displayName).toBe('slug')
    }
  })
  it('tolerates absent, malformed and non-object snapshots', () => {
    for (const value of [undefined, '', '{', 'null', '[]', 'true']) expect(getVersionPresentation(value)).toEqual({})
  })
})
