import * as React from 'react'
import { cn } from '@/shared/lib/utils'

interface TabsProps extends Omit<React.ComponentPropsWithRef<'div'>, 'defaultValue' | 'onChange'> {
  defaultValue?: string
  value?: string
  children: React.ReactNode
  className?: string
  onValueChange?: (value: string) => void
}

interface TabsContextValue {
  value: string
  setValue: (value: string) => void
  baseId: string
}

const TabsContext = React.createContext<TabsContextValue | undefined>(undefined)

export function Tabs({ defaultValue, value: controlledValue, children, className, onValueChange, ...props }: TabsProps) {
  const [internalValue, setInternalValue] = React.useState(defaultValue ?? '')
  const baseId = React.useId()
  const isControlled = controlledValue !== undefined
  const value = isControlled ? controlledValue : internalValue

  const handleValueChange = React.useCallback((nextValue: string) => {
    if (!isControlled) {
      setInternalValue(nextValue)
    }
    onValueChange?.(nextValue)
  }, [isControlled, onValueChange])

  return (
    <TabsContext.Provider value={{ value, setValue: handleValueChange, baseId }}>
      <div className={className} {...props}>{children}</div>
    </TabsContext.Provider>
  )
}

function getTriggers(list: HTMLElement) {
  return Array.from(list.querySelectorAll<HTMLButtonElement>('button[role="tab"]'))
    .filter(tab => tab.closest('[role="tablist"]') === list)
}

export function TabsList({ children, className, ref, ...props }: React.ComponentPropsWithRef<'div'>) {
  const listRef = React.useRef<HTMLDivElement>(null)
  React.useImperativeHandle(ref, () => listRef.current!, [])

  React.useLayoutEffect(() => {
    const list = listRef.current
    if (!list) return
    const updateTabStop = () => {
      const tabs = getTriggers(list)
      const entry = tabs.find(tab => !tab.disabled && tab.getAttribute('aria-selected') === 'true')
        ?? tabs.find(tab => !tab.disabled)
      // Restoring keyboard entry must not select a value or invoke business callbacks.
      tabs.forEach(tab => { tab.tabIndex = tab === entry ? 0 : -1 })
    }
    updateTabStop()
    // Descendants may change independently of TabsList (conditional or disabled triggers).
    const observer = new MutationObserver(updateTabStop)
    observer.observe(list, { subtree: true, childList: true, attributes: true, attributeFilter: ['disabled', 'aria-selected'] })
    return () => observer.disconnect()
  }, [])

  return (
    <div
      ref={listRef}
      role="tablist"
      className={cn('inline-flex items-center gap-6 border-b border-border text-sm', className)}
      {...props}
    >
      {children}
    </div>
  )
}

interface TabsTriggerProps extends React.ComponentPropsWithRef<'button'> {
  // IDs and their relationship are owned by Tabs, including for untyped callers.
  id?: never
  'aria-controls'?: never
  value: string
  children: React.ReactNode
  className?: string
  disabled?: boolean
}

export function TabsTrigger({ value, children, className, disabled = false, onClick, onKeyDown, ...props }: TabsTriggerProps) {
  const context = React.useContext(TabsContext)
  if (!context) throw new Error('TabsTrigger must be used within Tabs')

  const isActive = context.value === value

  return (
    <button
      type="button"
      role="tab"
      aria-selected={isActive}
      aria-disabled={disabled}
      tabIndex={isActive && !disabled ? 0 : -1}
      disabled={disabled}
      onClick={(event) => {
        onClick?.(event)
        if (!event.defaultPrevented) context.setValue(value)
      }}
      onKeyDown={(event) => {
        onKeyDown?.(event)
        if (event.defaultPrevented || event.altKey || event.ctrlKey || event.metaKey) return
        if (!['ArrowLeft', 'ArrowRight', 'Home', 'End'].includes(event.key)) return
        const list = event.currentTarget.closest<HTMLElement>('[role="tablist"]')
        if (!list) return
        const tabs = getTriggers(list).filter(tab => !tab.disabled)
        const index = tabs.indexOf(event.currentTarget)
        if (index < 0) return
        const direction = getComputedStyle(list).direction === 'rtl' ? -1 : 1
        const step = event.key === 'ArrowRight' ? direction : -direction
        const nextIndex = event.key === 'Home' ? 0 : event.key === 'End' ? tabs.length - 1 : (index + step + tabs.length) % tabs.length
        event.preventDefault()
        tabs[nextIndex].focus()
        tabs[nextIndex].click()
      }}
      data-state={isActive ? 'active' : 'inactive'}
      className={cn(
        'inline-flex -mb-px items-center justify-center whitespace-nowrap py-3 text-sm font-medium transition-[color,border-color,background-color] duration-150 focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring disabled:pointer-events-none disabled:opacity-50',
        isActive
          ? 'border-b-2 border-primary text-primary'
          : 'border-b-2 border-transparent text-muted-foreground hover:text-foreground/80',
        className
      )}
      {...props}
      id={`${context.baseId}-tab-${value}`}
      aria-controls={`${context.baseId}-panel-${value}`}
    >
      {children}
    </button>
  )
}

interface TabsContentProps extends React.ComponentPropsWithRef<'div'> {
  id?: never
  'aria-labelledby'?: never
  value: string
  children: React.ReactNode
  className?: string
}

export function TabsContent({ value, children, className, ...props }: TabsContentProps) {
  const context = React.useContext(TabsContext)
  if (!context) throw new Error('TabsContent must be used within Tabs')

  if (context.value !== value) return null

  return (
    <div
      role="tabpanel"
      tabIndex={0}
      className={cn('animate-fade-in focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-ring', className)}
      {...props}
      id={`${context.baseId}-panel-${value}`}
      aria-labelledby={`${context.baseId}-tab-${value}`}
    >
      {children}
    </div>
  )
}
