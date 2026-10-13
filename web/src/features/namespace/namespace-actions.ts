export type PendingNamespaceAction =
  | { action: 'freeze'; slug: string; name: string }
  | { action: 'unfreeze'; slug: string; name: string }
  | { action: 'archive'; slug: string; name: string }
  | { action: 'restore'; slug: string; name: string }
  | { action: 'delete'; slug: string; name: string }

type NamespaceActionCopy = {
  title: string
  description: string
  confirmText: string
  successTitle: string
  successDescription: string
  errorTitle: string
  variant: 'default' | 'destructive'
}

type NamespaceActionMutation = {
  mutateAsync: (input: { slug: string }) => Promise<unknown>
}

type NamespaceActionMutations = {
  freeze: NamespaceActionMutation
  unfreeze: NamespaceActionMutation
  archive: NamespaceActionMutation
  restore: NamespaceActionMutation
  delete: NamespaceActionMutation
}

type NamespaceActionToast = {
  success: (title: string, description?: string) => void
  error: (title: string, description?: string) => void
}

export function resolveNamespaceActionCopy(
  t: (key: string, options?: Record<string, unknown>) => string,
  action: PendingNamespaceAction['action'],
  name: string,
): NamespaceActionCopy {
  if (action === 'freeze') {
    return {
      title: t('myNamespaces.freezeConfirmTitle'),
      description: t('myNamespaces.freezeConfirmDescription', { name }),
      confirmText: t('myNamespaces.freeze'),
      successTitle: t('myNamespaces.freezeSuccessTitle'),
      successDescription: t('myNamespaces.freezeSuccessDescription', { name }),
      errorTitle: t('myNamespaces.freezeErrorTitle'),
      variant: 'default',
    }
  }
  if (action === 'unfreeze') {
    return {
      title: t('myNamespaces.unfreezeConfirmTitle'),
      description: t('myNamespaces.unfreezeConfirmDescription', { name }),
      confirmText: t('myNamespaces.unfreeze'),
      successTitle: t('myNamespaces.unfreezeSuccessTitle'),
      successDescription: t('myNamespaces.unfreezeSuccessDescription', { name }),
      errorTitle: t('myNamespaces.unfreezeErrorTitle'),
      variant: 'default',
    }
  }
  if (action === 'archive') {
    return {
      title: t('myNamespaces.archiveConfirmTitle'),
      description: t('myNamespaces.archiveConfirmDescription', { name }),
      confirmText: t('myNamespaces.archive'),
      successTitle: t('myNamespaces.archiveSuccessTitle'),
      successDescription: t('myNamespaces.archiveSuccessDescription', { name }),
      errorTitle: t('myNamespaces.archiveErrorTitle'),
      variant: 'destructive',
    }
  }
  if (action === 'delete') {
    return {
      title: t('myNamespaces.deleteConfirmTitle'),
      description: t('myNamespaces.deleteConfirmDescription', { name }),
      confirmText: t('myNamespaces.delete'),
      successTitle: t('myNamespaces.deleteSuccessTitle'),
      successDescription: t('myNamespaces.deleteSuccessDescription', { name }),
      errorTitle: t('myNamespaces.deleteErrorTitle'),
      variant: 'destructive',
    }
  }
  return {
    title: t('myNamespaces.restoreConfirmTitle'),
    description: t('myNamespaces.restoreConfirmDescription', { name }),
    confirmText: t('myNamespaces.restore'),
    successTitle: t('myNamespaces.restoreSuccessTitle'),
    successDescription: t('myNamespaces.restoreSuccessDescription', { name }),
    errorTitle: t('myNamespaces.restoreErrorTitle'),
    variant: 'default',
  }
}

export async function executeNamespaceAction(
  pendingAction: PendingNamespaceAction,
  mutations: NamespaceActionMutations,
  copy: NamespaceActionCopy,
  notifications: NamespaceActionToast,
) {
  try {
    if (pendingAction.action === 'freeze') {
      await mutations.freeze.mutateAsync({ slug: pendingAction.slug })
    } else if (pendingAction.action === 'unfreeze') {
      await mutations.unfreeze.mutateAsync({ slug: pendingAction.slug })
    } else if (pendingAction.action === 'archive') {
      await mutations.archive.mutateAsync({ slug: pendingAction.slug })
    } else if (pendingAction.action === 'delete') {
      await mutations.delete.mutateAsync({ slug: pendingAction.slug })
    } else {
      await mutations.restore.mutateAsync({ slug: pendingAction.slug })
    }

    notifications.success(copy.successTitle, copy.successDescription)
  } catch (error) {
    notifications.error(copy.errorTitle, error instanceof Error ? error.message : '')
    throw error
  }
}
