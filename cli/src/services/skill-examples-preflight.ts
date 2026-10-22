import { unzipSync } from 'fflate'
import { MAX_EXAMPLES_BYTES, parseSkillExamples } from './skill-examples'

export interface ExamplesPreflight {
  examplesStatus?: 'ready' | 'none' | 'invalid'
  examplesCount?: number
  warnings: string[]
}

/** Advisory inspection of the exact uploaded bytes; server package validation remains authoritative. */
export async function inspectPackagedExamples(archive: Blob): Promise<ExamplesPreflight> {
  try {
    const bytes = new Uint8Array(await archive.arrayBuffer())
    const entries: Array<{ name: string; path: string; size: number; storedSize?: number }> = []
    let entryCount = 0
    // Collect names without inflating arbitrary resources or an oversized examples declaration.
    unzipSync(bytes, { filter: file => {
      if (++entryCount > 500) throw new Error('Package entry limit')
      const path = file.name.replaceAll('\\', '/')
      const basename = path.split('/').at(-1) ?? ''
      if (!path.endsWith('/') && !path.startsWith('__MACOSX/') && path !== '__MACOSX'
        && basename !== '.DS_Store' && !basename.startsWith('._')) {
        const parts = path.split('/').filter(part => part !== '.' && part !== '')
        if (basename.toLowerCase() === 'skill.md') parts[parts.length - 1] = 'SKILL.md'
        entries.push({ name: file.name, path: parts.join('/'), size: file.originalSize,
          ...(file.compression === 0 ? { storedSize: file.size } : {}) })
      }
      return false
    } })
    // Match the existing server extractor's single-root stripping and single-SKILL promotion.
    if (entries.length && entries.every(entry => entry.path.includes('/'))
      && new Set(entries.map(entry => entry.path.split('/')[0])).size === 1) {
      for (const entry of entries) entry.path = entry.path.slice(entry.path.indexOf('/') + 1)
    }
    if (!entries.some(entry => entry.path === 'SKILL.md')) {
      const roots = entries.filter(entry => /^[^/]+\/SKILL\.md$/.test(entry.path)).map(entry => entry.path.slice(0, -'SKILL.md'.length))
      if (roots.length === 1) for (const entry of entries) {
        entry.path = entry.path.startsWith(roots[0]!) ? entry.path.slice(roots[0]!.length) : ''
      }
    }
    const candidates = entries.filter(entry => entry.path === '.skillhub/examples.json')
    const entry = candidates[0]
    if (!entry) return { examplesStatus: 'none', examplesCount: 0, warnings: [] }
    if (candidates.length !== 1 || entry.size > MAX_EXAMPLES_BYTES
      || (entry.storedSize !== undefined && (entry.storedSize > MAX_EXAMPLES_BYTES || entry.storedSize !== entry.size))) return invalid()
    const selected = unzipSync(bytes, { filter: file => file.name === entry.name && file.originalSize <= MAX_EXAMPLES_BYTES
      && (file.compression !== 0 || (file.size <= MAX_EXAMPLES_BYTES && file.size === file.originalSize)) })[entry.name]
    const parsed = selected ? parseSkillExamples(selected) : { state: 'invalid' as const }
    if (parsed.state !== 'ready') return invalid()
    const count = Object.values(parsed.items).reduce((total, items) => total + items.length, 0)
    return { examplesStatus: count ? 'ready' : 'none', examplesCount: count, warnings: [] }
  } catch {
    return { warnings: ['Task examples could not be inspected locally; server package validation will determine the result.'] }
  }
}

function invalid(): ExamplesPreflight {
  return { examplesStatus: 'invalid', warnings: ['.skillhub/examples.json is not a supported task-examples declaration; it remains ordinary package content.'] }
}

export function examplesPreflightLine(result: ExamplesPreflight): string | undefined {
  if (result.examplesStatus === 'ready') return `Task examples: ${result.examplesCount} entries across all declared languages.`
  if (result.examplesStatus === 'none') return 'Task examples: none.'
  return undefined
}
