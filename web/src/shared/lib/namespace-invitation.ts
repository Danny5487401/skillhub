import { toRouterPath } from './base-path'

const STORAGE_KEY = 'skillhub.namespace.invitation'

// Run before route guards inspect the location: a login redirect must never copy the fragment.
export function captureInvitationFragment() {
  if (typeof window === 'undefined' || !window.location.hash) return
  if (toRouterPath(window.location.pathname) !== '/join') return
  const token = window.location.hash.slice(1)
  try {
    if (/^[A-Za-z0-9_-]{43}$/.test(token)) window.sessionStorage.setItem(STORAGE_KEY, token)
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
  return `${publicBase}/join#${token}`
}
