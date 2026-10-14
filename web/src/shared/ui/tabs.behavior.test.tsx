// @vitest-environment jsdom
import { createRef, useState } from 'react'
import { cleanup, fireEvent, render, screen, waitFor } from '@testing-library/react'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { Tabs, TabsList, TabsTrigger, TabsContent } from './tabs'

afterEach(cleanup)

function Example({ value, onValueChange = vi.fn() }: { value?: string; onValueChange?: (value: string) => void }) {
  return (
    <Tabs value={value} onValueChange={onValueChange}>
      <TabsList aria-label="Options">
        <TabsTrigger value="a">Alpha</TabsTrigger>
        <TabsTrigger value="b" disabled>Beta</TabsTrigger>
        <TabsTrigger value="c">Gamma</TabsTrigger>
      </TabsList>
      <TabsContent value="a">Alpha panel</TabsContent>
      <TabsContent value="c">Gamma panel</TabsContent>
    </Tabs>
  )
}

describe('Tabs keyboard contract', () => {
  it.each([undefined, 'missing', 'b'])('restores keyboard entry without choosing for value %s', (value) => {
    const onValueChange = vi.fn()
    render(<Example value={value} onValueChange={onValueChange} />)
    const tabs = screen.getAllByRole('tab')
    expect(tabs.map(tab => tab.tabIndex)).toEqual([0, -1, -1])
    expect(onValueChange).not.toHaveBeenCalled()
    expect(screen.queryByRole('tabpanel')).toBeNull()
  })

  it('navigates, wraps and skips disabled tabs; links the active panel', async () => {
    render(<Example />)
    const alpha = screen.getByRole('tab', { name: 'Alpha' })
    const gamma = screen.getByRole('tab', { name: 'Gamma' })
    fireEvent.keyDown(alpha, { key: 'ArrowRight' })
    expect(document.activeElement).toBe(gamma)
    const panel = screen.getByRole('tabpanel')
    expect(panel.id).toBe(gamma.getAttribute('aria-controls'))
    expect(panel.getAttribute('aria-labelledby')).toBe(gamma.id)
    await waitFor(() => expect(gamma.tabIndex).toBe(0))
    fireEvent.keyDown(gamma, { key: 'ArrowRight' })
    expect(document.activeElement).toBe(alpha)
    fireEvent.keyDown(alpha, { key: 'End' })
    expect(document.activeElement).toBe(gamma)
    fireEvent.keyDown(gamma, { key: 'Home' })
    expect(document.activeElement).toBe(alpha)
  })

  it('honors RTL direction and controlled selection', () => {
    const onValueChange = vi.fn()
    render(
      <Tabs value="a" onValueChange={onValueChange}>
        <TabsList style={{ direction: 'rtl' }}>
          <TabsTrigger value="a">Alpha</TabsTrigger>
          <TabsTrigger value="b">Beta</TabsTrigger>
          <TabsTrigger value="c">Gamma</TabsTrigger>
        </TabsList>
        <TabsContent value="a">Alpha panel</TabsContent>
      </Tabs>
    )
    fireEvent.keyDown(screen.getByRole('tab', { name: 'Alpha' }), { key: 'ArrowRight' })
    expect(document.activeElement).toBe(screen.getByRole('tab', { name: 'Gamma' }))
    expect(onValueChange).toHaveBeenCalledWith('c')
    expect(screen.getByRole('tab', { name: 'Alpha' }).getAttribute('aria-selected')).toBe('true')
    expect(screen.getByRole('tabpanel').textContent).toBe('Alpha panel')
  })

  it('repairs entry after a descendant independently removes or disables selection', async () => {
    const onValueChange = vi.fn()
    function Triggers() {
      const [state, setState] = useState(0)
      return <>
        <button onClick={() => setState(state + 1)}>Change</button>
        {state < 2 && <TabsTrigger value="a" disabled={state === 1}>Alpha</TabsTrigger>}
        <TabsTrigger value="c" disabled={state === 3}>Gamma</TabsTrigger>
      </>
    }
    render(<Tabs value="a" onValueChange={onValueChange}><TabsList><Triggers /></TabsList></Tabs>)
    fireEvent.click(screen.getByText('Change'))
    await waitFor(() => expect(screen.getByRole('tab', { name: 'Gamma' }).tabIndex).toBe(0))
    fireEvent.click(screen.getByText('Change'))
    await waitFor(() => expect(screen.getByRole('tab', { name: 'Gamma' }).tabIndex).toBe(0))
    fireEvent.click(screen.getByText('Change'))
    await waitFor(() => expect(screen.getByRole('tab', { name: 'Gamma' }).tabIndex).toBe(-1))
    expect(onValueChange).not.toHaveBeenCalled()
  })

  it('reserves linked IDs in types and at runtime for untyped callers', () => {
    // @ts-expect-error Linked IDs are managed by Tabs rather than the caller.
    const trigger = <TabsTrigger value="a" id="custom-tab" aria-controls="missing-panel">Alpha</TabsTrigger>
    // @ts-expect-error Linked IDs are managed by Tabs rather than the caller.
    const content = <TabsContent value="a" id="custom-panel" aria-labelledby="missing-tab">Panel</TabsContent>
    render(<Tabs defaultValue="a"><TabsList>{trigger}</TabsList>{content}</Tabs>)
    const tab = screen.getByRole('tab')
    const panel = screen.getByRole('tabpanel')
    expect(tab.id).not.toBe('custom-tab')
    expect(panel.id).not.toBe('custom-panel')
    expect(document.getElementById(tab.getAttribute('aria-controls')!)).toBe(panel)
    expect(document.getElementById(panel.getAttribute('aria-labelledby')!)).toBe(tab)
  })

  it('forwards refs/native attributes and allows event cancellation', () => {
    const listRef = createRef<HTMLDivElement>()
    const triggerRef = createRef<HTMLButtonElement>()
    const onValueChange = vi.fn()
    render(
      <Tabs defaultValue="a" onValueChange={onValueChange}>
        <TabsList ref={listRef} aria-label="Choices" style={{ padding: 3 }}>
          <TabsTrigger ref={triggerRef} value="a" title="First" onKeyDown={event => event.preventDefault()}>Alpha</TabsTrigger>
          <TabsTrigger value="c" onClick={event => event.preventDefault()}>Gamma</TabsTrigger>
        </TabsList>
      </Tabs>
    )
    expect(listRef.current).toBe(screen.getByRole('tablist'))
    expect(triggerRef.current?.title).toBe('First')
    expect(listRef.current?.style.padding).toBe('3px')
    fireEvent.keyDown(triggerRef.current!, { key: 'End' })
    fireEvent.click(screen.getByRole('tab', { name: 'Gamma' }))
    expect(onValueChange).not.toHaveBeenCalled()
  })
})
