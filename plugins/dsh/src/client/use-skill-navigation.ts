import type { Context } from '@deepseek-ai/cordis';
import { SessionId } from '@deepseek-ai/dsh-session/types';
import type {} from '@deepseek-ai/dsh-client-ui-workspace/client';
import type {} from '@deepseek-ai/dsh-api-workspace-controller/client';
import type {} from '@deepseek-ai/dsh-client-ui-layout/client';
import type { InstalledSkillList } from '../host/types.js';
import type { SkillDraftRetentions } from './use-skill-drafts.js';
import { exactInstalledSkill } from './use-skill.js';

/** Create a fresh Host session: a reusable blank may already hold an unsent draft. */
export async function newSkillConversation(ctx: Context, originalId: string, revision: string, key: string, signal: AbortSignal,
  installed: (sessionId: string, revision: string, signal: AbortSignal) => Promise<InstalledSkillList>, drafts: Pick<SkillDraftRetentions, 'preserve'>) {
  const id = SessionId(originalId);
  const scope = ctx.sessions.scope(id);
  const binding = ctx.sessions.binding(id);
  const cwd = ctx.sessions.list.getSnapshot().byId[id]?.cwd;
  const layout = ctx.get('layout');
  const navigation = ctx.get('uiWorkspace');
  const workspaces = ctx.get('workspaces');
  if (!scope || !binding || !cwd || !layout || !navigation || !workspaces) throw new Error('请先打开工作区，再使用此技能。');
  const workspace = workspaces.list.getSnapshot().items.find(item => item.sessionIds.includes(id));
  if (!workspace) throw new Error('当前会话不属于可用工作区，请先打开工作区。');
  const combined = AbortSignal.any([signal, layout.beginNavigation()]);
  let invalidated = false;
  const assertOriginal = () => {
    if (invalidated || combined.aborted || ctx.sessions.scope(id) !== scope || ctx.sessions.binding(id) !== binding
      || ctx.sessions.list.getSnapshot().byId[id]?.cwd !== cwd || !ctx.sessions.list.getSnapshot().byId[id]?.retainedBy.mainView
      || !ctx.get('workspaces')?.list.getSnapshot().items.some(item => item.workspaceId === workspace.workspaceId && item.sessionIds.includes(id))) {
      throw new Error('工作区或会话已变化，请重新点击使用。');
    }
  };
  const stop = ctx.sessions.list.subscribe(() => { try { assertOriginal(); } catch { invalidated = true; } });
  const stopWorkspace = workspaces.list.subscribe(() => { try { assertOriginal(); } catch { invalidated = true; } });
  let reference: ReturnType<Context['sessions']['retain']> | undefined;
  const releaseDrafts: (() => void)[] = [];
  let navigated = false;
  try {
    assertOriginal();
    const before = await installed(originalId, revision, combined);
    assertOriginal();
    const identity = before.items.find(item => item.itemKey === key);
    if (!identity || !exactInstalledSkill(before, identity)) throw new Error('安装记录已变化，请刷新已安装列表。');
    releaseDrafts.push(drafts.preserve(originalId));
    let created: SessionId;
    try { created = await ctx.sessions.create({ workspaceId: workspace.workspaceId, cwd }); }
    catch { assertOriginal(); throw new Error('新对话创建失败，原对话和草稿已保留，请重试。'); }
    assertOriginal();
    reference = ctx.sessions.retain(created, { source: 'workspaceOperation', signal: combined });
    await reference.ready;
    assertOriginal();
    if (created === id || ctx.sessions.list.getSnapshot().byId[created]?.cwd !== cwd) throw new Error('新对话工作区不匹配，请重试。');
    const after = await installed(created, revision, combined);
    assertOriginal();
    const item = exactInstalledSkill(after, identity);
    if (!item) throw new Error('新对话中的安装记录已变化，请刷新已安装列表。');
    // Capture any attachment/reference added while Host creation and inventory reads were pending.
    releaseDrafts.push(drafts.preserve(originalId));
    stop(); stopWorkspace();
    navigation.openSession(created);
    navigated = true;
    return { sessionId: created, itemKey: item.itemKey };
  } finally { stop(); stopWorkspace(); reference?.release(); if (!navigated) for (const release of releaseDrafts) release(); }
}
