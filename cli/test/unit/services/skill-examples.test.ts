import { expect, test } from 'bun:test'
import { mkdir, readFile, rm, symlink, writeFile } from 'node:fs/promises'
import { join } from 'node:path'
import { createTempHome } from '../../helpers/temp-env'
import { parseSkillExamples, readSkillExamples } from '../../../src/services/skill-examples'
import { snapshotSkillDirectory, diffSkillFiles } from '../../../src/services/skill-fingerprint'
import { assertOwnedRegularTree } from '../../../src/integrations/recovery-files'

const example = { id: 'summary', title: '会议摘要', prompt: '请用 {{skill}} 总结会议。' }
const valid = { schemaVersion: 1, defaultLocale: 'zh-CN', examples: { 'zh-CN': [example], en: [{ ...example, title: 'Summary', prompt: 'Use {{skill}}.' }] } }
const bytes = (value: unknown) => new TextEncoder().encode(JSON.stringify(value))

test('examples recognizes bounded multilingual object protocol without changing authored bytes', () => {
  const parsed = parseSkillExamples(bytes(valid))
  expect(parsed.state).toBe('ready')
  if (parsed.state !== 'ready') throw new Error('expected ready')
  expect(parsed.items['zh-CN']).toEqual([example])
  expect(parsed.digest).toHaveLength(64)
  expect(parseSkillExamples(new Uint8Array([0xef, 0xbb, 0xbf, ...bytes(valid)]))).toMatchObject({ state: 'ready' })
  expect(parseSkillExamples(bytes({ ...valid, examples: { en: [] }, defaultLocale: 'en' }))).toMatchObject({ state: 'ready', items: { en: [] } })
})

test('examples rejects unsupported or ambiguous declarations, Unicode lengths and encoding', () => {
  const invalid = [
    {}, { ...valid, schemaVersion: 2 }, { ...valid, extra: true }, { ...valid, defaultLocale: 'ja' },
    { ...valid, examples: { 'zh-CN': [example], 'zh-cn': [] } },
    { ...valid, examples: { 'zh-CN': [example, example] } },
    { ...valid, examples: { 'zh-CN': ['old string format'] } },
    ...[{ ...example, id: 'Bad ID' }, { ...example, title: '\u0085\uFEFF' }, { ...example, prompt: '' },
      { ...example, title: '😀'.repeat(81) }, { ...example, prompt: '😀'.repeat(4001) }, { ...example, extra: 1 }]
      .map(item => ({ ...valid, examples: { 'zh-CN': [item] } }))
  ]
  for (const value of invalid) expect(parseSkillExamples(bytes(value))).toEqual({ state: 'invalid' })
  expect(parseSkillExamples(new Uint8Array([0xff]))).toEqual({ state: 'invalid' })
  expect(parseSkillExamples(new Uint8Array(65537))).toEqual({ state: 'invalid' })
  expect(parseSkillExamples(bytes({ ...valid, examples: { 'zh-CN': [{ ...example, title: '😀'.repeat(80) }] } }))).toMatchObject({ state: 'ready' })
})

test('packaged examples participate in fingerprint and recovery ownership even when malformed', async () => {
  const env = await createTempHome()
  const root = join(env.cwd, 'skill')
  await mkdir(join(root, '.skillhub'), { recursive: true })
  await writeFile(join(root, 'SKILL.md'), '# skill')
  const path = join(root, '.skillhub/examples.json')
  await writeFile(path, bytes(valid))
  const snapshot = await snapshotSkillDirectory(root)
  await writeFile(join(root, '.skillhub/metadata.json'), JSON.stringify({ registry: 'https://example.com', namespace: 'a', slug: 'b', version: '1', ...snapshot }))
  await expect(assertOwnedRegularTree(root)).resolves.toBeUndefined()
  expect(await readSkillExamples(root)).toMatchObject({ state: 'ready' })
  expect((await snapshotSkillDirectory(root)).fingerprint).toBe(snapshot.fingerprint)
  await writeFile(path, '{broken')
  expect(await readSkillExamples(root)).toEqual({ state: 'invalid' })
  expect(diffSkillFiles(snapshot.files, (await snapshotSkillDirectory(root)).files)).toEqual(['.skillhub/examples.json'])
  expect(await readFile(path, 'utf8')).toBe('{broken')
  await writeFile(join(root, '.skillhub/untracked.txt'), 'preserve')
  await expect(assertOwnedRegularTree(root)).rejects.toThrow('Untracked recovery metadata content')
  await rm(join(root, '.skillhub/untracked.txt'))
  await rm(path)
  expect(await readSkillExamples(root)).toEqual({ state: 'none' })
  await symlink(join(root, 'SKILL.md'), path)
  await expect(assertOwnedRegularTree(root)).rejects.toThrow('Unsafe recovery path')
})


test('recognizes the normative protocol examples shared with server and schema checks', async () => {
  for (const name of ['skill-examples.valid.json', 'skill-examples.empty.json']) {
    const content = await readFile(new URL('../../../../openspec/changes/add-dsh-native-plugin/contracts/' + name, import.meta.url))
    expect(parseSkillExamples(content)).toMatchObject({ state: 'ready' })
  }
})


test('examples honors inclusive raw-byte, locale and per-language collection bounds', () => {
  const encoded = bytes(valid)
  const padded = new Uint8Array(65536).fill(32)
  padded.set(encoded)
  expect(parseSkillExamples(padded)).toMatchObject({ state: 'ready' })
  const entries = Array.from({ length: 20 }, (_, index) => ({ ...example, id: `case-${index}` }))
  expect(parseSkillExamples(bytes({ ...valid, examples: { 'zh-CN': entries } }))).toMatchObject({ state: 'ready' })
  expect(parseSkillExamples(bytes({ ...valid, examples: { 'zh-CN': [...entries, { ...example, id: 'extra' }] } }))).toEqual({ state: 'invalid' })
  const languages = Object.fromEntries(['en', 'zh', 'ja', 'ko', 'fr', 'de', 'es', 'it', 'pt', 'ru'].map(locale => [locale, []]))
  expect(parseSkillExamples(bytes({ ...valid, defaultLocale: 'en', examples: languages }))).toMatchObject({ state: 'ready' })
  expect(parseSkillExamples(bytes({ ...valid, defaultLocale: 'en', examples: { ...languages, nl: [] } }))).toEqual({ state: 'invalid' })
})
