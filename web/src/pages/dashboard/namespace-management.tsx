import { useParams } from '@tanstack/react-router'
import { NamespaceHeader } from '@/features/namespace/namespace-header'
import { NamespaceManagementNav } from '@/features/namespace/namespace-management-nav'
import { useNamespaceDetail } from '@/shared/hooks/use-namespace-queries'
import { NamespaceSkills } from '@/features/namespace/namespace-skills'

export function NamespaceManagementPage() {
  const { slug } = useParams({ from: '/dashboard/namespaces/$slug' })
  const { data: namespace } = useNamespaceDetail(slug)
  return (
    <div className="space-y-6">
      {namespace ? <NamespaceHeader namespace={namespace} /> : null}
      <NamespaceManagementNav slug={slug} active="skills" />
      <NamespaceSkills key={slug} namespace={slug} showHeader={false} />
    </div>
  )
}
