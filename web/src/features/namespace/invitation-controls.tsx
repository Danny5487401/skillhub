import { useId, useState } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useTranslation } from 'react-i18next'
import { getAppBaseUrl, namespaceApi } from '@/api/client'
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
  const [confirm, setConfirm] = useState<'replace' | 'revoke' | null>(null)
  const [link, setLink] = useState<string | null>(null)
  const status = useQuery({ queryKey: ['namespace-invitation-status', slug], queryFn: () => namespaceApi.invitationStatus(slug), enabled: !compact })
  const create = useMutation({
    mutationFn: (replace: boolean) => namespaceApi.createInvitation(slug, replace),
    onSuccess: ({ token, replaced }) => {
      const url = invitationUrl(resolvePublicRegistryUrl(getAppBaseUrl(), window.location.origin, BASE_PATH), token)
      setLink(url)
      try {
        void navigator.clipboard.writeText(url)
          .then(() => toast.success(t(replaced ? 'invitation.replacedCopied' : 'invitation.copied')))
          .catch(() => toast.info(t('invitation.manualCopy')))
      } catch {
        toast.info(t('invitation.manualCopy'))
      }
      void queryClient.invalidateQueries({ queryKey: ['namespace-invitation-status', slug] })
    },
  })
  const revoke = useMutation({
    mutationFn: () => namespaceApi.revokeInvitation(slug),
    onSuccess: async () => {
      setLink(null)
      await queryClient.invalidateQueries({ queryKey: ['namespace-invitation-status', slug] })
      toast.success(t('invitation.revoked'))
    },
  })
  const handleCreate = async () => {
    const current = compact ? (await status.refetch()).data : status.data
    if (current?.active) setConfirm('replace')
    else if (current) create.mutate(false)
  }
  return (
    <div id={compact ? undefined : 'invite'} className="space-y-2 scroll-mt-24" onClick={compact ? (event) => event.stopPropagation() : undefined}>
      <div className="flex gap-2">
        <Button variant="outline" size={compact ? 'sm' : 'default'} disabled={(!compact && status.isPending) || status.isFetching || create.isPending} onClick={handleCreate}>{t('invitation.create')}</Button>
        {status.data?.active ? <Button variant="outline" disabled={revoke.isPending} onClick={() => setConfirm('revoke')}>{t('invitation.revoke')}</Button> : null}
      </div>
      {link ? (
        <div className="space-y-1">
          <label className="text-sm" htmlFor={linkId}>{t('invitation.link')}</label>
          <Input id={linkId} readOnly value={link} onFocus={(event) => event.currentTarget.select()} />
          <p className="text-xs text-muted-foreground">{t('invitation.linkHint')}</p>
        </div>
      ) : null}
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
