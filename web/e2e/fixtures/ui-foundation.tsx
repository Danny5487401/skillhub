import { useState } from 'react'
import { createRoot } from 'react-dom/client'
import { Tabs, TabsList, TabsTrigger, TabsContent } from '@/shared/ui/tabs'
import { Table, TableHeader, TableBody, TableRow, TableHead, TableCell } from '@/shared/ui/table'
import { Button } from '@/shared/ui/button'
import { Input } from '@/shared/ui/input'
import { Textarea } from '@/shared/ui/textarea'
import { Card, CardHeader } from '@/shared/ui/card'
import '@/index.css'

const params = new URLSearchParams(location.search)
const mode = params.get('mode')
document.documentElement.classList.toggle('dark', params.get('theme') === 'dark')
document.documentElement.dir = params.get('dir') === 'rtl' ? 'rtl' : 'ltr'

function Fixture() {
  const [value, setValue] = useState(mode === 'missing' || mode === 'all-disabled' ? 'missing' : mode === 'empty' || mode === 'none' ? '' : mode === 'selected' ? 'second' : 'first')
  const [changes, setChanges] = useState(0)
  const [disabled, setDisabled] = useState(mode === 'disabled')
  const [removed, setRemoved] = useState(false)
  const allDisabled = mode === 'all-disabled'
  return <main data-density={params.get('density') ?? 'regular'}>
    <button id="before">Before tabs</button>
    <Tabs {...(mode === 'none' ? {} : { value })} onValueChange={next => { setValue(next); setChanges(count => count + 1) }}>
      <TabsList><TabsTrigger value="first" disabled={allDisabled}>First</TabsTrigger>
        {!removed && <TabsTrigger value="second" disabled={allDisabled || disabled}>Second</TabsTrigger>}
        <TabsTrigger value="third" disabled={allDisabled}>Third</TabsTrigger></TabsList>
      <TabsContent value="first">first content</TabsContent><TabsContent value="second">second content</TabsContent><TabsContent value="third">third content</TabsContent>
    </Tabs>
    <button id="after">After tabs</button>
    <button onClick={() => setDisabled(true)}>Disable second</button><button onClick={() => setRemoved(true)}>Remove second</button>
    <output data-testid="changes">{changes}</output>
    <Table><TableHeader><TableRow><TableHead>Skill</TableHead></TableRow></TableHeader><TableBody><TableRow><TableCell>Example</TableCell></TableRow></TableBody></Table>
    <label htmlFor="normal">Name</label><Input id="normal" />
    <label htmlFor="invalid">Version</label><Input id="invalid" aria-invalid="true" aria-describedby="error" /><p id="error">Enter a valid version.</p>
    <Textarea aria-label="Description" aria-invalid="true" />
    <Button data-testid="default-control">Save</Button><Button variant="soft">Soft</Button>
    <Button data-testid="class-override" className="h-12">Large caller</Button>
    <Input aria-label="Style override" style={{ height: 52 }} />
    <Card className="shadow-none" data-testid="card"><CardHeader>Card</CardHeader></Card>
  </main>
}

createRoot(document.getElementById('root')!).render(<Fixture />)
