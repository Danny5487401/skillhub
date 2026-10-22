import { createHash } from 'node:crypto'
import { constants } from 'node:fs'
import { lstat, open } from 'node:fs/promises'
import { join } from 'node:path'

export interface SkillExample { id: string; title: string; prompt: string }
export type SkillExamples =
  | { state: 'ready'; digest: string; defaultLocale: string; items: Record<string, SkillExample[]> }
  | { state: 'none' }
  | { state: 'invalid' }
  | { state: 'unavailable'; retryable: boolean }

export const MAX_EXAMPLES_BYTES = 65_536
const localePattern = /^[a-z]{2,3}(?:-[A-Za-z0-9]{2,8})*$/
// Explicit protocol whitespace code points, including ASCII controls.
// eslint-disable-next-line no-control-regex
const nonWhitespace = /[^\u0009-\u000D\u0020\u0085\u00A0\u1680\u2000-\u200A\u2028\u2029\u202F\u205F\u3000\uFEFF]/u

/** Parse author bytes without rewriting the immutable packaged file. */
export function parseSkillExamples(bytes: Uint8Array): SkillExamples {
  if (bytes.byteLength > MAX_EXAMPLES_BYTES) return { state: 'invalid' }
  try {
    const text = new TextDecoder('utf-8', { fatal: true, ignoreBOM: true }).decode(bytes).replace(/^\uFEFF/, '')
    const value: unknown = JSON.parse(text)
    if (!record(value) || !exactKeys(value, ['schemaVersion', 'defaultLocale', 'examples'])
      || value.schemaVersion !== 1 || typeof value.defaultLocale !== 'string' || !localePattern.test(value.defaultLocale)
      || !record(value.examples)) return { state: 'invalid' }
    const languages = Object.keys(value.examples)
    if (languages.length < 1 || languages.length > 10) return { state: 'invalid' }
    const normalized = new Set<string>()
    const items: Record<string, SkillExample[]> = Object.create(null)
    for (const locale of languages) {
      const entries = value.examples[locale]
      if (!localePattern.test(locale) || normalized.has(locale.toLowerCase())
        || !Array.isArray(entries) || entries.length > 20) return { state: 'invalid' }
      normalized.add(locale.toLowerCase())
      const ids = new Set<string>()
      const cases: SkillExample[] = []
      for (const entry of entries) {
        if (!record(entry) || !exactKeys(entry, ['id', 'title', 'prompt']) || typeof entry.id !== 'string'
          || entry.id.length > 64 || !/^[a-z0-9]+(?:-[a-z0-9]+)*$/.test(entry.id) || ids.has(entry.id)
          || !textWithin(entry.title, 80) || !textWithin(entry.prompt, 4000)) return { state: 'invalid' }
        ids.add(entry.id)
        cases.push({ id: entry.id, title: entry.title, prompt: entry.prompt })
      }
      items[locale] = cases
    }
    if (!normalized.has(value.defaultLocale.toLowerCase())) return { state: 'invalid' }
    return { state: 'ready', digest: createHash('sha256').update(bytes).digest('hex'), defaultLocale: value.defaultLocale, items }
  } catch { return { state: 'invalid' } }
}

/** Host-only fixed-path read; installation and winner verification remain the caller's responsibility. */
export async function readSkillExamples(directory: string): Promise<SkillExamples> {
  try {
    const parent = await lstat(join(directory, '.skillhub'))
    if (!parent.isDirectory() || parent.isSymbolicLink()) return { state: 'unavailable', retryable: false }
    const path = join(directory, '.skillhub', 'examples.json')
    const info = await lstat(path)
    if (!info.isFile() || info.isSymbolicLink()) return { state: 'unavailable', retryable: false }
    const file = await open(path, constants.O_RDONLY | (constants.O_NOFOLLOW ?? 0) | (constants.O_NONBLOCK ?? 0))
    try {
      if (!(await file.stat()).isFile()) return { state: 'unavailable', retryable: false }
      const bytes = new Uint8Array(MAX_EXAMPLES_BYTES + 1)
      let size = 0
      while (size < bytes.length) {
        const { bytesRead } = await file.read(bytes, size, bytes.length - size, size)
        if (!bytesRead) break
        size += bytesRead
      }
      return parseSkillExamples(bytes.subarray(0, size))
    } finally { await file.close() }
  } catch (error) {
    return (error as NodeJS.ErrnoException).code === 'ENOENT'
      ? { state: 'none' } : { state: 'unavailable', retryable: true }
  }
}

function record(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value)
}
function exactKeys(value: Record<string, unknown>, keys: string[]): boolean {
  return Object.keys(value).length === keys.length && keys.every(key => Object.hasOwn(value, key))
}
function textWithin(value: unknown, maximum: number): value is string {
  return typeof value === 'string' && [...value].length <= maximum && nonWhitespace.test(value)
}
