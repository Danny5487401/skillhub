import { expect, test } from 'bun:test';
import { Context } from '@deepseek-ai/cordis';
import { SessionId } from '@deepseek-ai/dsh-session/types';
import { newSkillConversation } from '../src/client/use-skill-navigation.js';
import type { InstalledSkillList } from '../src/host/types.js';

function fixture() {
  const ctx = new Context(); const scope = new Context(); const binding = {};
  const listeners = new Set<() => void>(), workspaceListeners = new Set<() => void>();
  const navigation = new AbortController();
  let current = true, created = 0, retained = 0, released = 0, opened = '', createFailure = false;
  let cwd = '/project';
  const rows = () => ({ old: { cwd: '/project', retainedBy: { mainView: current ? 1 : 0 } }, fresh: { cwd, retainedBy: {} } });
  let onCreate = () => {};
  ctx.provide('sessions', {
    scope: () => scope, binding: () => binding,
    list: { getSnapshot: () => ({ byId: rows() }), subscribe: (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener); }; } },
    create: async (options: unknown) => { expect(options).toEqual({ workspaceId: 'workspace', cwd: '/project' }); created++; onCreate(); if (createFailure) throw new Error('Host unavailable'); return SessionId('fresh'); },
    retain: (_id: unknown, options: unknown) => { retained++; expect(options).toMatchObject({ source: 'workspaceOperation' }); return { ready: Promise.resolve({}), release: () => { released++; } }; },
  } as never);
  ctx.provide('workspaces', { list: { getSnapshot: () => ({ items: [{ workspaceId: 'workspace', sessionIds: [SessionId('old')] }] }), subscribe: (listener: () => void) => { workspaceListeners.add(listener); return () => { workspaceListeners.delete(listener); }; } } } as never);
  ctx.provide('layout', { beginNavigation: () => navigation.signal } as never);
  ctx.provide('uiWorkspace', { openSession: (id: string) => { opened = id; } } as never);
  const original = { itemKey: 'old-key', namespace: 'global', slug: 'writer', version: '1', scope: 'user', state: 'present' } as const;
  const reads: string[] = [];
  let fresh = { ...original, itemKey: 'new-key' } as InstalledSkillList['items'][number];
  const installed = async (id: string): Promise<InstalledSkillList> => { reads.push(id); return { complete: true, unavailable: [], issues: [], items: [id === 'old' ? original : fresh] }; };
  let preserve = () => () => {};
  const use = () => newSkillConversation(ctx, 'old', 'source', 'old-key', new AbortController().signal, installed, { preserve: () => preserve() });
  return { use, reads, listeners, workspaceListeners, preserve: (callback: typeof preserve) => { preserve = callback; }, counts: () => ({ created, retained, released, opened }),
    create: (callback: () => void) => { onCreate = callback; }, fail: () => { createFailure = true; },
    supersede: () => navigation.abort(), move: () => { cwd = '/other'; },
    change: () => { current = false; for (const listener of listeners) listener(); current = true; for (const listener of listeners) listener(); },
    fresh: (value: Partial<typeof fresh>) => { fresh = { ...fresh, ...value }; },
  };
}
test('fresh same-workspace session remaps the exact user install identity and releases temporary ownership', async () => {
  const f = fixture(); expect(await f.use()).toEqual({ sessionId: SessionId('fresh'), itemKey: 'new-key' });
  expect(f.reads).toEqual(['old', 'fresh']); expect(f.counts()).toEqual({ created: 1, retained: 1, released: 1, opened: 'fresh' });
  expect(f.listeners.size).toBe(0); expect(f.workspaceListeners.size).toBe(0);
});
for (const change of [{ version: '2' }, { scope: 'project' }, { slug: 'other' }, { namespace: 'other' }, { state: 'missing' }] as const) {
  test(`new session cannot substitute changed installation ${JSON.stringify(change)}`, async () => {
    const f = fixture(); f.fresh(change); await expect(f.use()).rejects.toThrow('安装记录已变化');
    expect(f.counts().opened).toBe(''); expect(f.counts().released).toBe(1);
  });
}
for (const scenario of ['failure', 'superseded', 'session-ABA', 'wrong-cwd'] as const) {
  test(`navigation ${scenario} leaves the original view and releases all temporary references`, async () => {
    const f = fixture();
    if (scenario === 'failure') f.fail();
    if (scenario === 'superseded') f.create(f.supersede);
    if (scenario === 'session-ABA') f.create(f.change);
    if (scenario === 'wrong-cwd') f.create(f.move);
    await expect(f.use()).rejects.toThrow();
    expect(f.counts().opened).toBe(''); expect(f.counts().retained).toBe(f.counts().released);
    expect(f.listeners.size).toBe(0); expect(f.workspaceListeners.size).toBe(0);
  });
}


test('protected draft capacity refuses navigation before creating another session', async () => {
  const f = fixture(); f.preserve(() => { throw new Error('已有 8 个对话保留草稿'); });
  await expect(f.use()).rejects.toThrow('8 个对话'); expect(f.counts().created).toBe(0); expect(f.counts().opened).toBe('');
});
test('native origin draft protection is rechecked immediately before navigation and rolled back on failure', async () => {
  const f = fixture(); let checks = 0, rollbacks = 0;
  f.preserve(() => { if (++checks === 2) throw new Error('late attachment cannot be retained'); return () => { rollbacks++; }; });
  await expect(f.use()).rejects.toThrow('late attachment'); expect(checks).toBe(2); expect(rollbacks).toBe(1);
  expect(f.counts().opened).toBe(''); expect(f.counts().retained).toBe(f.counts().released);
});
