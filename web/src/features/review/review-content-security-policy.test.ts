/** @vitest-environment jsdom */

import html from '../../../index.html?raw'
import { describe, expect, it } from 'vitest'

describe('review diff content security policy', () => {
  it('permits the bundled diff worker without permitting blob scripts', () => {
    const document = new DOMParser().parseFromString(html, 'text/html')
    const policy = document.querySelector('meta[http-equiv="Content-Security-Policy"]')?.getAttribute('content')
    const directives = policy?.split(';').map((directive) => directive.trim()) ?? []

    expect(directives.find((directive) => directive.startsWith('worker-src '))).toBe("worker-src 'self' blob:")
    expect(directives.find((directive) => directive.startsWith('script-src '))).not.toContain('blob:')
  })
})
