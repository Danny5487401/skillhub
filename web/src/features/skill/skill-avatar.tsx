import { useState } from 'react'
import { buildApiUrl } from '@/api/client'

export function SkillAvatar({ name, url, size = 'h-10 w-10' }: { name: string; url?: string | null; size?: string }) {
  const [failedUrl, setFailedUrl] = useState<string | null>(null)
  const imageUrl = url && failedUrl !== url ? buildApiUrl(url) : null
  return (
    <span className={`inline-flex shrink-0 items-center justify-center overflow-hidden rounded-lg bg-primary/10 text-primary font-semibold ${size}`} aria-hidden="true">
      {imageUrl ? <img src={imageUrl} alt="" className="h-full w-full object-cover" onError={() => setFailedUrl(url ?? null)} /> : (Array.from(name.trim())[0] ?? '?').toLocaleUpperCase()}
    </span>
  )
}
