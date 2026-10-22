import type { Context } from '@deepseek-ai/cordis';
import { SessionId } from '@deepseek-ai/dsh-session/types';
import type {} from '@deepseek-ai/dsh-api-session-controller/client';
import type {} from '@deepseek-ai/dsh-client-ui-conversation/client';
import type {} from '@deepseek-ai/dsh-client-ui-layout/client';
import type { SkillUseCapture } from './use-skill.js';

/** Cordis creates a fresh caller-context proxy on each service lookup. */
function serviceIdentity(value: object): unknown {
  return Reflect.get(value, Symbol.for('cordis.original')) ?? value;
}

/** Capture the actual retained session and native shell, not transient service facades. */
export function captureSkillUse(ctx: Context, sessionId: string, actions: SkillUseCapture['actions']): SkillUseCapture {
  const id = SessionId(sessionId);
  const scope = ctx.sessions.scope(id);
  const binding = ctx.sessions.binding(id);
  const cwd = ctx.sessions.list.getSnapshot().byId[id]?.cwd;
  const conversation = ctx.get('conversation');
  const layout = ctx.get('layout');
  if (!scope || !binding || !conversation || !layout) throw new Error('请先打开当前会话，再使用此技能。');
  const input = conversation.input.for(scope);
  let invalidated = false;
  const assertCurrent = () => {
    const current = ctx.sessions.list.getSnapshot().byId[id];
    const currentConversation = ctx.get('conversation');
    const currentLayout = ctx.get('layout');
    if (invalidated || ctx.sessions.scope(id) !== scope || ctx.sessions.binding(id) !== binding || current?.cwd !== cwd
      || !current?.retainedBy.mainView || !currentConversation || !currentLayout
      || serviceIdentity(currentConversation) !== serviceIdentity(conversation)
      || serviceIdentity(currentLayout) !== serviceIdentity(layout)
      || currentConversation.input.for(scope) !== input) throw new Error('当前会话或输入框已变化，请重试。');
  };
  const stop = ctx.sessions.list.subscribe(() => { try { assertCurrent(); } catch { invalidated = true; } });
  return { actions, input, assertCurrent, dispose: stop };
}
