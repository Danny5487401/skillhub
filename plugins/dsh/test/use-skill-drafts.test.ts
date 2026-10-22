import { expect, test } from 'bun:test';
import { Context } from '@deepseek-ai/cordis';
import type { InputState } from '@deepseek-ai/dsh-client-ui-conversation/client';
import { SkillDraftRetentions } from '../src/client/use-skill-drafts.js';

function fixture() {
  const ctx = new Context();
  const listListeners = new Set<() => void>();
  const records = new Map<string, ReturnType<typeof record>>();
  function record(draft = '未发送草稿') {
    let state: InputState = { draft, attachmentIds: [], occurrences: [], draftRev: 1, phase: 'plain', queue: [] };
    let sessionState = { removed: false, pendingSubmissions: [] as unknown[] };
    const inputListeners = new Set<() => void>(), sessionListeners = new Set<() => void>();
    const scope = new Context();
    const input = { state: { getSnapshot: () => state, subscribe: (listener: () => void) => { inputListeners.add(listener); return () => { inputListeners.delete(listener); }; } } };
    const session = { getSnapshot: () => sessionState, subscribe: (listener: () => void) => { sessionListeners.add(listener); return () => { sessionListeners.delete(listener); }; } };
    return { scope, binding: { session }, input, main: 1, refs: 0, releases: 0,
      state: () => state, update: (value: Partial<InputState>) => { state = { ...state, ...value }; for (const listener of inputListeners) listener(); },
      pending: (count: number) => { sessionState = { ...sessionState, pendingSubmissions: Array(count).fill({}) }; for (const listener of sessionListeners) listener(); },
      remove: () => { sessionState = { ...sessionState, removed: true }; for (const listener of sessionListeners) listener(); },
      listeners: () => inputListeners.size + sessionListeners.size,
    };
  }
  ctx.provide('sessions', {
    scope: (id: string) => records.get(id)?.scope, binding: (id: string) => records.get(id)?.binding,
    list: { getSnapshot: () => ({ byId: Object.fromEntries([...records].map(([id, value]) => [id, { retainedBy: { mainView: value.main } }])) }),
      subscribe: (listener: () => void) => { listListeners.add(listener); return () => { listListeners.delete(listener); }; } },
    retain: (id: string, options: unknown) => { expect(options).toEqual({ source: 'workspaceOperation' }); const value = records.get(id)!; value.refs++;
      return { ready: Promise.resolve(value.binding), release: () => { value.refs--; value.releases++; } }; },
  } as never);
  ctx.provide('conversation', { input: { for: (scope: Context) => [...records.values()].find(value => value.scope === scope)!.input } } as never);
  const drafts = new SkillDraftRetentions(ctx);
  return { drafts, listListeners, add: (id: string, draft?: string) => { const value = record(draft); records.set(id, value); return value; },
    notify: () => { for (const listener of listListeners) listener(); },
  };
}
test('native origin binding stays retained while away and transfers ownership when mainView returns', () => {
  const f = fixture(), original = f.add('old');
  const state = original.state(); f.drafts.preserve('old'); expect(original.refs).toBe(1);
  original.main = 0; f.notify(); expect(original.refs).toBe(1); expect(original.state()).toBe(state);
  original.main = 1; f.notify(); expect(original.refs).toBe(0); expect(original.releases).toBe(1);
  expect(original.listeners()).toBe(0); expect(f.listListeners.size).toBe(0); f.drafts.dispose(); expect(original.releases).toBe(1);
});
for (const content of ['attachment', 'reference', 'claimed', 'adjudicating', 'submitting', 'queue', 'pending-submission'] as const) {
  test(`empty text still protects native ${content} without copying or rewriting it`, () => {
    const f = fixture(), original = f.add('old', '');
    if (content === 'attachment') original.update({ attachmentIds: ['native-image-id'] as never });
    if (content === 'reference') original.update({ occurrences: [{ id: 'native-chip' }] as never });
    if (content === 'claimed') original.update({ phase: 'claimed', claim: { name: 'command', token: '/command' } });
    if (content === 'adjudicating' || content === 'submitting') original.update({ phase: content });
    if (content === 'queue') original.update({ queue: [{}] as never });
    if (content === 'pending-submission') original.pending(1);
    const state = original.state(); f.drafts.preserve('old'); original.main = 0; f.notify();
    expect(original.refs).toBe(1); expect(original.state()).toBe(state); f.drafts.dispose(); expect(original.refs).toBe(0);
  });
}
test('a truly empty origin needs no retained reference', () => {
  const f = fixture(), original = f.add('old', ''); f.drafts.preserve('old')(); expect(original.refs).toBe(0); expect(original.listeners()).toBe(0); f.drafts.dispose();
});
test('cleared draft releases after native submission settlement; a same-turn restored draft stays protected', async () => {
  const f = fixture(), original = f.add('old'); f.drafts.preserve('old'); original.main = 0; f.notify();
  original.update({ draft: '' }); original.update({ draft: '发送失败恢复的草稿' }); await Bun.sleep(5); expect(original.refs).toBe(1);
  original.update({ draft: '' }); original.pending(1); await Bun.sleep(5); expect(original.refs).toBe(1);
  original.pending(0); await Bun.sleep(5); expect(original.refs).toBe(0); f.drafts.dispose();
});
test('navigation failure rollback, session removal and plugin disposal release native references once', () => {
  const f = fixture(), original = f.add('old'); const rollback = f.drafts.preserve('old'); rollback(); rollback(); expect(original.releases).toBe(1);
  f.drafts.preserve('old'); original.remove(); expect(original.releases).toBe(2);
  const other = f.add('other'); f.drafts.preserve('other'); f.drafts.dispose(); f.drafts.dispose(); expect(other.refs).toBe(0); expect(other.releases).toBe(1); expect(f.listListeners.size).toBe(0);
});
test('eight protected drafts are bounded without eviction; ninth is rejected and a returned origin frees capacity', () => {
  const f = fixture(); const originals = Array.from({ length: 9 }, (_, i) => f.add(`old-${i}`));
  for (let i = 0; i < 8; i++) { f.drafts.preserve(`old-${i}`); originals[i]!.main = 0; f.notify(); }
  expect(() => f.drafts.preserve('old-8')).toThrow('8 个对话'); expect(originals.slice(0, 8).every(value => value.refs === 1)).toBe(true); expect(originals[8]!.refs).toBe(0);
  originals[0]!.main = 1; f.notify(); f.drafts.preserve('old-8'); expect(originals[8]!.refs).toBe(1); f.drafts.dispose(); expect(originals.every(value => value.refs === 0)).toBe(true);
});


test('replacement of the actual original scope releases its retained generation', () => {
  const f = fixture(), original = f.add('old'); f.drafts.preserve('old'); original.main = 0; f.notify();
  f.add('old'); f.notify(); expect(original.refs).toBe(0); expect(original.releases).toBe(1); expect(original.listeners()).toBe(0); f.drafts.dispose();
});
