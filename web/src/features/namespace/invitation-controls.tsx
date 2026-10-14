import { useId, useRef, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { getAppBaseUrl, namespaceApi } from '@/api/client'
import { useAuth } from '@/features/auth/use-auth'
import { ApiError } from '@/shared/lib/api-error'
import { ConfirmDialog } from '@/shared/components/confirm-dialog'
import { BASE_PATH } from '@/shared/lib/base-path'
import { invitationUrl } from '@/shared/lib/namespace-invitation'
import { resolvePublicRegistryUrl } from '@/shared/lib/registry-url'
import { toast } from '@/shared/lib/toast'
import { Button } from '@/shared/ui/button'
import { Input } from '@/shared/ui/input'

export function InvitationControls({ slug, compact = false }: { slug: string; compact?: boolean }) {
  const { t } = useTranslation()
  const linkId = useId()
  const queryClient = useQueryClient()
  const { user } = useAuth()
  const storageKey = `skillhub.namespace.issued-invitation:${user?.userId ?? ''}:${slug}`
  const inMemoryToken = useRef<{ key: string; token: string } | null>(null)
  const [storageUnavailable, setStorageUnavailable] = useState(false)
  const [confirm, setConfirm] = useState<'replace' | 'revoke' | null>(null)
  const clearStoredToken = () => {
    inMemoryToken.current = null
    try { window.sessionStorage.removeItem(storageKey) } catch { /* Storage may be unavailable. */ }
  }
  const status = useQuery({
    queryKey: ['namespace-invitation-status', slug, user?.userId],
    enabled: !compact && !!user,
    staleTime: 0,
    queryFn: async () => {
      const current = await namespaceApi.invitationStatus(slug)
      if (!current.active) {
        clearStoredToken()
        return { active: false, token: null }
      }
      let token = inMemoryToken.current?.key === storageKey ? inMemoryToken.current.token : null
      try { token = window.sessionStorage.getItem(storageKey) ?? token } catch { /* Use the current dialog's copy. */ }
      if (!token) return { active: true, token: null }
      try {
        const preview = await namespaceApi.previewInvitation(token)
        if (preview.namespaceSlug !== slug || Date.parse(preview.expiresAt) <= Date.now()) {
          clearStoredToken()
          return { active: true, token: null }
        }
      } catch (error) {
        if (!(error instanceof ApiError) || error.status !== 404) throw error
        clearStoredToken()
        return { active: true, token: null }
      }
      return { active: true, token }
    },
  })
  const link = !status.isFetching && !status.isError && status.data?.token
    ? invitationUrl(resolvePublicRegistryUrl(getAppBaseUrl(), window.location.origin, BASE_PATH), status.data.token)
    : null
  const copyLink = async (url: string, replaced = false) => {
    try {
      await navigator.clipboard.writeText(url)
      toast.success(t(replaced ? 'invitation.replacedCopied' : 'invitation.copied'))
    } catch { toast.info(t('invitation.manualCopy')) }
  }
  const create = useMutation({
    mutationFn: (replace: boolean) => namespaceApi.createInvitation(slug, replace),
    onSuccess: async ({ token, replaced }) => {
      inMemoryToken.current = { key: storageKey, token }
      try { window.sessionStorage.setItem(storageKey, token) } catch { setStorageUnavailable(true) }
      const url = invitationUrl(resolvePublicRegistryUrl(getAppBaseUrl(), window.location.origin, BASE_PATH), token)
      await copyLink(url, replaced)
      await queryClient.invalidateQueries({ queryKey: ['namespace-invitation-status', slug] })
    },
  })
  const revoke = useMutation({
    mutationFn: () => namespaceApi.revokeInvitation(slug),
    onSuccess: async () => {
      clearStoredToken()
      await queryClient.invalidateQueries({ queryKey: ['namespace-invitation-status', slug] })
      toast.success(t('invitation.revoked'))
    },
  })
  const handleCreate = async () => {
    const result = await status.refetch()
    if (result.isError || !result.data) return
    const current = result.data
    if (current.active) setConfirm('replace')
    else create.mutate(false)
  }
  return (
    <div id={compact ? undefined : 'invite'} className="space-y-2 scroll-mt-24" onClick={compact ? (event) => event.stopPropagation() : undefined}>
      <div className="flex gap-2">
        <Button variant="outline" size={compact ? 'sm' : 'default'} disabled={!user || (!compact && status.isPending) || status.isFetching || create.isPending} onClick={handleCreate}>{t('invitation.create')}</Button>
        {status.data?.active ? <Button variant="outline" disabled={revoke.isPending} onClick={() => setConfirm('revoke')}>{t('invitation.revoke')}</Button> : null}
      </div>
      {link ? (
        <div className="space-y-1">
          <label className="text-sm" htmlFor={linkId}>{t('invitation.link')}</label>
          <div className="flex gap-2">
            <Input id={linkId} readOnly value={link} onFocus={(event) => event.currentTarget.select()} />
            <Button variant="outline" onClick={() => void copyLink(link)}>{t('invitation.copy')}</Button>
          </div>
          <p className="text-xs text-muted-foreground">{t(storageUnavailable ? 'invitation.storageUnavailable' : 'invitation.localLinkHint')}</p>
        </div>
      ) : null}
      {!link && !status.isFetching && !status.isError && status.data?.active ? <p className="text-sm text-muted-foreground">{t('invitation.activeUnavailable')}</p> : null}
      {(create.error || revoke.error || status.error) ? <p role="alert" className="text-sm text-destructive">{t('invitation.error')}</p> : null}
      <ConfirmDialog open={confirm !== null} onOpenChange={(open) => { if (!open) setConfirm(null) }}
        title={t(confirm === 'revoke' ? 'invitation.revokeTitle' : 'invitation.replaceTitle')}
        description={t(confirm === 'revoke' ? 'invitation.revokeDescription' : 'invitation.replaceDescription')}
        confirmText={t('dialog.confirm')}
        onConfirm={async () => {
          try {
            if (confirm === 'revoke') await revoke.mutateAsync()
            else await create.mutateAsync(true)
          } catch {
            setConfirm(null)
          }
        }} />
    </div>
  )
}
