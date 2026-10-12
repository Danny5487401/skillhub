// @vitest-environment jsdom
import { beforeEach, describe, expect, it } from 'vitest'
import { captureInvitationFragment, clearInvitationToken, invitationUrl, storedInvitationToken } from './namespace-invitation'

const token = 'A'.repeat(43)

describe('namespace invitation URL handoff', () => {
  beforeEach(() => {
    clearInvitationToken()
    window.history.replaceState(null, '', '/')
  })

  it('moves a token from the fragment to tab-scoped storage before login routing', () => {
    window.history.replaceState(null, '', `/join?from=mail#${token}`)
    captureInvitationFragment()
    expect(storedInvitationToken()).toBe(token)
    expect(window.location.pathname).toBe('/join')
    expect(window.location.search).toBe('?from=mail')
    expect(window.location.hash).toBe('')
  })

  it('rejects malformed fragments and still removes them from the URL', () => {
    window.history.replaceState(null, '', '/join#bad-token')
    captureInvitationFragment()
    expect(storedInvitationToken()).toBeNull()
    expect(window.location.hash).toBe('')
  })

  it('uses a resolved public base path in the shared link', () => {
    expect(invitationUrl('https://example.com/skillhub', token)).toBe(`https://example.com/skillhub/join#${token}`)
  })
})
