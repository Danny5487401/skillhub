// @vitest-environment jsdom
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { captureInvitationFragment, clearInvitationToken, invitationUrl, storedInvitationToken } from './namespace-invitation'

const token = 'A'.repeat(43)

describe('namespace invitation URL handoff', () => {
  beforeEach(() => {
    clearInvitationToken()
    window.history.replaceState(null, '', '/')
  })

  afterEach(() => { vi.unstubAllEnvs(); vi.resetModules() })

  it.each([token, `invite=${token}`])('captures fragment %s in tab storage before login routing', fragment => {
    window.history.replaceState(null, '', `/join?from=mail#${fragment}`)
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
    expect(invitationUrl('https://example.com/skillhub', token)).toBe(`https://example.com/skillhub/join#invite=${token}`)
  })

  it.each(['bad-token', 'invite=bad-token', `invite=${token}&extra=1`, `invite=${token}&invite=${token}`, `other=${token}`, `invite=%41${'A'.repeat(42)}`])('clears an earlier stored invitation when a new fragment is malformed: %s', fragment => {
    window.history.replaceState(null, '', `/join#${token}`)
    captureInvitationFragment()
    expect(storedInvitationToken()).toBe(token)
    window.history.replaceState(null, '', `/join?from=mail#${fragment}`)
    captureInvitationFragment()
    expect(storedInvitationToken()).toBeNull()
    expect(window.location.hash).toBe('')
    expect(window.location.search).toBe('?from=mail')
  })

  it('clears an earlier stored token for an explicit empty fragment', () => {
    window.history.replaceState(null, '', `/join#invite=${token}`)
    captureInvitationFragment()
    window.history.replaceState(null, '', '/join#')
    expect(window.location.hash).toBe('')
    expect(window.location.href.endsWith('#')).toBe(true)
    captureInvitationFragment()
    expect(storedInvitationToken()).toBeNull()
    expect(window.location.href.endsWith('#')).toBe(false)
  })

  it('replaces an earlier invitation with the new named token', () => {
    window.history.replaceState(null, '', `/join#${token}`)
    captureInvitationFragment()
    const next = '_'.repeat(43)
    window.history.replaceState(null, '', `/join#invite=${next}`)
    captureInvitationFragment()
    expect(storedInvitationToken()).toBe(next)
  })

  it('keeps the stored invitation after returning from login without a fragment', () => {
    window.history.replaceState(null, '', `/join#invite=${token}`)
    captureInvitationFragment()
    window.history.replaceState(null, '', '/join')
    captureInvitationFragment()
    expect(storedInvitationToken()).toBe(token)
  })

  it('does not capture or clear fragments belonging to other routes', () => {
    window.history.replaceState(null, '', `/search#invite=${token}`)
    captureInvitationFragment()
    expect(storedInvitationToken()).toBeNull()
    expect(window.location.hash).toBe(`#invite=${token}`)
  })

  it('captures and clears the named token under a deployment base path', async () => {
    vi.stubEnv('BASE_URL', '/skillhub/')
    vi.resetModules()
    const invitation = await import('./namespace-invitation')
    window.history.replaceState({ from: 'mail' }, '', `/skillhub/join?from=mail#invite=${token}`)
    invitation.captureInvitationFragment()
    expect(invitation.storedInvitationToken()).toBe(token)
    expect(window.location.pathname).toBe('/skillhub/join')
    expect(window.location.search).toBe('?from=mail')
    expect(window.location.hash).toBe('')
    expect(window.history.state).toEqual({ from: 'mail' })
  })

})
