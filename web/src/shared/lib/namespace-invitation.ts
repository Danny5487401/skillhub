import { toRouterPath } from './base-path'

const STORAGE_KEY = 'skillhub.namespace.invitation'

// Run before route guards inspect the location: a login redirect must never copy the fragment.
export function captureInvitationFragment() {
  if (typeof window === 'undefined' || !window.location.href.includes('#')) return
  if (toRouterPath(window.location.pathname) !== '/join') return
  const fragment = window.location.hash.slice(1)
  const token = fragment.startsWith('invite=') ? fragment.slice('invite='.length) : fragment
  try {
    if (/^[A-Za-z0-9_-]{43}$/.test(token)) window.sessionStorage.setItem(STORAGE_KEY, token)
    else window.sessionStorage.removeItem(STORAGE_KEY)
  } finally {
    window.history.replaceState(window.history.state, '', `${window.location.pathname}${window.location.search}`)
  }
}

export function storedInvitationToken(): string | null {
  if (typeof window === 'undefined') return null
  return window.sessionStorage.getItem(STORAGE_KEY)
}

export function clearInvitationToken() {
  if (typeof window !== 'undefined') window.sessionStorage.removeItem(STORAGE_KEY)
}

export function invitationUrl(publicBase: string, token: string): string {
  return `${publicBase}/join#invite=${token}`
}
