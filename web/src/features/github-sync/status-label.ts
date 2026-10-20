import type { TFunction } from 'i18next'

export function githubSyncStatusLabel(t: TFunction, code?: string) {
  if (!code) return '—'
  return t(`githubSync.codes.${code}`, { defaultValue: code })
}
