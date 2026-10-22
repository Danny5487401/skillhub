import { expect, test } from 'bun:test';
import { Context, Service } from '@deepseek-ai/cordis';
import { SessionId } from '@deepseek-ai/dsh-session/types';
import type { InputState } from '@deepseek-ai/dsh-client-ui-conversation/client';
import { captureSkillUse } from '../src/client/use-skill-context.js';
import { SkillUseController } from '../src/client/use-skill.js';

async function fixture() {
  const ctx = new Context();
  const scope = new Context();
  const binding = {};
  const listeners = new Set<() => void>();
  let record = { cwd: '/workspace', retainedBy: { mainView: 1 } };
  let state: InputState = { draft: '原草稿', draftRev: 1, phase: 'plain', attachmentIds: [], occurrences: [], queue: [] };
  const input = { state: { getSnapshot: () => state }, focus() {} };
  class Conversation extends Service { input = { for: () => input }; constructor(ctx: Context) { super(ctx, 'conversation'); } }
  class Layout extends Service { constructor(ctx: Context) { super(ctx, 'layout'); } selectPanel() {} }
  const cleanup = [ctx.provide('sessions', {
    scope: () => scope, binding: () => binding,
    list: { getSnapshot: () => ({ byId: { [SessionId('session')]: record } }), subscribe: (listener: () => void) => { listeners.add(listener); return () => { listeners.delete(listener); }; } },
  } as never)];
  let conversation = ctx.plugin(Conversation); let layout = ctx.plugin(Layout);
  await conversation; await layout;
  const actions = { captureInsertion: () => ({ start: 0, end: 0, draftRev: state.draftRev }), insertText: (text: string) => { state = { ...state, draft: text + state.draft, draftRev: state.draftRev + 1 }; return true; } };
  const controller = new SkillUseController({ capture: (sessionId, actions) => captureSkillUse(ctx, sessionId, actions), revision: async () => 'source',
    prepare: async () => ({ ok: true, value: { status: 'ready', name: 'verified-name', version: '1', scope: 'project', examples: { state: 'none' } } }), reveal() {} });
  controller.bind('session', actions);
  return { ctx, controller, actions, state: () => state, listeners, update: (next: typeof record) => { record = next; for (const listener of listeners) listener(); },
    replace: async (kind: 'conversation' | 'layout') => {
      if (kind === 'conversation') { await conversation.dispose(); conversation = ctx.plugin(Conversation); await conversation; }
      else { await layout.dispose(); layout = ctx.plugin(Layout); await layout; }
    },
    cleanup: async () => { controller.dispose(); await conversation.dispose(); await layout.dispose(); for (const remove of cleanup) await remove(); },
  };
}

test('real Cordis service facades change identity without blocking native draft preparation', async () => {
  const f = await fixture();
  try {
    expect(f.ctx.get('conversation')).not.toBe(f.ctx.get('conversation'));
    expect(f.ctx.get('layout')).not.toBe(f.ctx.get('layout'));
    await f.controller.services('session').useSkill('source', 'item', new AbortController().signal);
    expect(f.state().draft).toContain('请使用 /verified-name'); expect(f.state().draft).toContain('原草稿');
    expect(f.listeners.size).toBe(0);
  } finally { await f.cleanup(); }
});
for (const kind of ['conversation', 'layout'] as const) {
  test(`real Cordis ${kind} replacement invalidates the captured input even if its methods are unchanged`, async () => {
    const f = await fixture(); const capture = captureSkillUse(f.ctx, 'session', f.actions);
    try { capture.assertCurrent(); await f.replace(kind); expect(capture.assertCurrent).toThrow('当前会话或输入框已变化'); }
    finally { capture.dispose?.(); await f.cleanup(); }
  });
}
test('real session subscription latches workspace and main-view changes even after switching back', async () => {
  for (const change of [{ cwd: '/other', retainedBy: { mainView: 1 } }, { cwd: '/workspace', retainedBy: { mainView: 0 } }]) {
    const f = await fixture(); const capture = captureSkillUse(f.ctx, 'session', f.actions);
    try { f.update(change); f.update({ cwd: '/workspace', retainedBy: { mainView: 1 } }); expect(capture.assertCurrent).toThrow(); }
    finally { capture.dispose?.(); await f.cleanup(); }
  }
});
