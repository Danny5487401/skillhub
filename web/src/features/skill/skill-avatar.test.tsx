/** @vitest-environment jsdom */
import { cleanup, fireEvent, render, screen } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'

vi.mock('@/api/client', () => ({ buildApiUrl: (url: string) => `/proxy${url}` }))
import { SkillAvatar } from './skill-avatar'

describe('SkillAvatar', () => {
  afterEach(cleanup)
  it('shows the skill initial when no image is approved', () => {
    render(<SkillAvatar name="Weather" />)
    expect(screen.getByText('W')).toBeTruthy()
  })

  it('falls back to the initial if an approved image is unavailable', () => {
    const { container } = render(<SkillAvatar name="Weather" url="/avatar" />)
    const image = container.querySelector('img')!
    expect(image.getAttribute('src')).toBe('/proxy/avatar')
    fireEvent.error(image)
    expect(screen.getByText('W')).toBeTruthy()
  })
})
