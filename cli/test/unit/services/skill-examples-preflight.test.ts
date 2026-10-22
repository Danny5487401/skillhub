import { expect, spyOn, test } from 'bun:test'
import { zipSync } from 'fflate'
import { inspectPackagedExamples } from '../../../src/services/skill-examples-preflight'

const valid = JSON.stringify({ schemaVersion: 1, defaultLocale: 'zh-CN', examples: {
  'zh-CN': [], en: [{ id: 'one', title: 'Summary', prompt: 'Use {{skill}}.' }]
} })
function archive(files: Record<string, string>): Blob {
  return new Blob([new Uint8Array(zipSync(Object.fromEntries(Object.entries(files).map(([path, text]) => [path, new TextEncoder().encode(text)])))).buffer])
}

test('preflight matches server root stripping/promotion and counts all language entries', async () => {
  for (const files of [
    { 'SKILL.md': '# skill', '.skillhub/examples.json': valid },
    { 'package/SKILL.md': '# skill', 'package/.skillhub/examples.json': valid },
    { 'package/SKILL.md': '# skill', 'package/.skillhub/examples.json': valid, 'README.md': 'outside' },
    { 'package/skill.md': '# skill', 'package/.skillhub/examples.json': valid, 'README.md': 'outside' },
    { 'package/Skill.MD': '# skill', 'package/.skillhub/examples.json': valid, 'README.md': 'outside' },
    { 'package/SKILL.md': '# skill', 'package/.skillhub/examples.json': valid, '__MACOSX/._file': 'ignored' }
  ]) expect(await inspectPackagedExamples(archive(files))).toEqual({ examplesStatus: 'ready', examplesCount: 1, warnings: [] })
})

test('preflight bounds inflation, preserves invalid declaration semantics and ignores unrelated files', async () => {
  for (const content of ['{broken', 'x'.repeat(65537)]) {
    const result = await inspectPackagedExamples(archive({ 'SKILL.md': '# skill', '.skillhub/examples.json': content }))
    expect(result.examplesStatus).toBe('invalid')
    expect(result.examplesCount).toBeUndefined()
    expect(result.warnings).toHaveLength(1)
  }
  expect(await inspectPackagedExamples(archive({ 'SKILL.md': '# skill', 'docs/.skillhub/examples.json': valid })))
    .toEqual({ examplesStatus: 'none', examplesCount: 0, warnings: [] })
  const unreadable = await inspectPackagedExamples(new Blob(['not a zip']))
  expect(unreadable.examplesStatus).toBeUndefined()
  expect(unreadable.warnings).toHaveLength(1)
})


test('preflight rejects forged stored-file sizes without copying the oversized entry body', async () => {
  const content = new Uint8Array(65537).fill(120)
  const bytes = zipSync({ 'SKILL.md': new TextEncoder().encode('# skill'), '.skillhub/examples.json': content }, { level: 0 })
  const view = new DataView(bytes.buffer, bytes.byteOffset, bytes.byteLength)
  let forged = false
  for (let offset = 0; offset <= bytes.byteLength - 46; offset++) {
    if (view.getUint32(offset, true) !== 0x02014b50) continue
    const nameLength = view.getUint16(offset + 28, true)
    const name = new TextDecoder().decode(bytes.subarray(offset + 46, offset + 46 + nameLength))
    if (name === '.skillhub/examples.json') {
      expect(view.getUint16(offset + 10, true)).toBe(0)
      expect(view.getUint32(offset + 20, true)).toBe(65537)
      view.setUint32(offset + 24, 1, true)
      forged = true
      break
    }
  }
  expect(forged).toBe(true)
  const subarray = Uint8Array.prototype.subarray
  let oversizedReads = 0
  const spy = spyOn(Uint8Array.prototype, 'subarray').mockImplementation(function (this: Uint8Array, begin, end) {
    if ((end ?? this.length) - (begin ?? 0) > 65536) oversizedReads++
    return subarray.call(this, begin, end)
  })
  try {
    expect(await inspectPackagedExamples(new Blob([new Uint8Array(bytes).buffer])))
      .toMatchObject({ examplesStatus: 'invalid', warnings: [expect.stringContaining('ordinary package content')] })
    expect(oversizedReads).toBe(0)
  } finally { spy.mockRestore() }
})
