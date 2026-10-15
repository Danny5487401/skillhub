import { useParams } from '@tanstack/react-router'
import { NamespaceSkills } from '@/features/namespace/namespace-skills'

/** Public namespace metadata and discoverable skills. */
export function NamespacePage() {
  const { namespace } = useParams({ from: '/space/$namespace' })
  return <NamespaceSkills namespace={namespace} />
}
