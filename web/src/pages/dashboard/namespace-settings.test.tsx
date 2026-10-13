import { createElement, type ReactNode } from 'react'
import { renderToStaticMarkup } from 'react-dom/server'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import type { ManagedNamespace } from '@/api/types'

const { state } = vi.hoisted(() => ({ state: { namespace: null as ManagedNamespace | null, roles: [] as string[] } }))
vi.mock('@tanstack/react-router', () => ({
  useParams: () => ({ slug: 'team' }),
  useNavigate: () => vi.fn(),
  Link: ({ children, to, ...props }: { children: ReactNode; to: string }) => createElement('a', { ...props, href: to }, children),
}))
vi.mock('react-i18next', () => ({ useTranslation: () => ({ t: (key: string) => key }) }))
vi.mock('@/features/auth/use-auth', () => ({ useAuth: () => ({ hasRole: (role: string) => state.roles.includes(role) }) }))
vi.mock('@/features/namespace/namespace-header', () => ({ NamespaceHeader: () => null }))
vi.mock('@/shared/components/confirm-dialog', () => ({ ConfirmDialog: () => null }))
vi.mock('@/shared/hooks/use-namespace-queries', () => ({
  useNamespaceDetail: () => ({ data: state.namespace, isLoading: false }),
  useMyNamespaces: () => ({ data: state.namespace ? [state.namespace] : [] }),
  useArchiveNamespace: () => ({ mutateAsync: vi.fn() }),
  useDeleteNamespace: () => ({ mutateAsync: vi.fn() }),
  useFreezeNamespace: () => ({ mutateAsync: vi.fn() }),
  useRestoreNamespace: () => ({ mutateAsync: vi.fn() }),
  useUnfreezeNamespace: () => ({ mutateAsync: vi.fn() }),
}))
import { NamespaceSettingsPage } from './namespace-settings'
import { NamespaceManagementNav } from '@/features/namespace/namespace-management-nav'

beforeEach(() => {
  state.roles = []
  state.namespace = { id: 2, slug: 'team', displayName: 'Team', type: 'TEAM', status: 'ACTIVE', createdAt: '', immutable: false, currentUserRole: 'OWNER', canFreeze: true, canUnfreeze: false, canArchive: true, canRestore: false, canDelete: false }
})

describe('Namespace management permissions', () => {
  it('shows only lifecycle actions the server permits in settings', () => {
    const html = renderToStaticMarkup(createElement(NamespaceSettingsPage))
    expect(html).toContain('freeze-namespace-team')
    expect(html).toContain('archive-namespace-team')
    expect(html).not.toContain('delete-namespace-team')
    expect(html).not.toContain('restore-namespace-team')
    expect(html).not.toContain('unfreeze-namespace-team')
  })
  it('never exposes lifecycle changes for the global namespace', () => {
    Object.assign(state.namespace!, { type: 'GLOBAL', canDelete: true, canFreeze: true, canArchive: true })
    const html = renderToStaticMarkup(createElement(NamespaceSettingsPage))
    expect(html).toContain('namespaceManagement.globalReadOnly')
    expect(html).not.toContain('freeze-namespace-team')
    expect(html).not.toContain('archive-namespace-team')
    expect(html).not.toContain('delete-namespace-team')
  })
  it('lets platform skill reviewers access reviews without exposing member management', () => {
    Object.assign(state.namespace!, { currentUserRole: undefined })
    state.roles = ['SKILL_ADMIN']
    const html = renderToStaticMarkup(createElement(NamespaceManagementNav, { slug: 'team', active: 'reviews' }))
    expect(html).toContain('namespaceManagement.reviews')
    expect(html).not.toContain('namespaceManagement.members')
  })
  it('does not offer team review access to ordinary members', () => {
    Object.assign(state.namespace!, { currentUserRole: 'MEMBER' })
    const html = renderToStaticMarkup(createElement(NamespaceManagementNav, { slug: 'team', active: 'members' }))
    expect(html).toContain('namespaceManagement.members')
    expect(html).not.toContain('namespaceManagement.reviews')
  })
})
