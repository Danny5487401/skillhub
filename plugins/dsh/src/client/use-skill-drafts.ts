import type { Context } from '@deepseek-ai/cordis';
import { SessionId } from '@deepseek-ai/dsh-session/types';
import type { InputState } from '@deepseek-ai/dsh-client-ui-conversation/client';
import type {} from '@deepseek-ai/dsh-api-session-controller/client';

/** Native scope ownership, not a second draft/attachment store. */
export class SkillDraftRetentions {
  private held = new Map<string, () => void>();
  private disposed = false;
  constructor(private ctx: Context, private limit = 8) {}
  preserve(sessionId: string): () => void {
    if (this.disposed) throw new Error('技能使用服务已关闭，请重新打开插件。');
    if (this.held.has(sessionId)) return () => {};
    const id = SessionId(sessionId);
    const scope = this.ctx.sessions.scope(id), binding = this.ctx.sessions.binding(id);
    const conversation = this.ctx.get('conversation');
    if (!scope || !binding || !conversation) throw new Error('原对话输入框不可用，请重试。');
    const input = conversation.input.for(scope);
    const hasContent = (state: InputState) => state.draft.length > 0 || state.attachmentIds.length > 0 || state.occurrences.length > 0
      || state.phase !== 'plain' || state.claim !== undefined || state.queue.length > 0 || binding.session.getSnapshot().pendingSubmissions.length > 0;
    if (!hasContent(input.state.getSnapshot())) return () => {};
    if (this.held.size >= this.limit) throw new Error('已有 8 个对话保留了未完成草稿，请先返回这些对话处理草稿，再使用技能新建对话。');
    const reference = this.ctx.sessions.retain(id, { source: 'workspaceOperation' });
    // retain() starts an existing binding's open waiter; ownership itself is synchronous.
    void reference.ready.catch(() => release());
    let departed = false, released = false;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const stops: (() => void)[] = [];
    const release = () => {
      if (released) return;
      released = true; if (timer) clearTimeout(timer);
      this.held.delete(sessionId); for (const stop of stops) stop(); reference.release();
    };
    const check = () => {
      if (released) return;
      if (this.ctx.sessions.scope(id) !== scope || this.ctx.sessions.binding(id) !== binding || binding.session.getSnapshot().removed) { release(); return; }
      const main = (this.ctx.sessions.list.getSnapshot().byId[id]?.retainedBy.mainView ?? 0) > 0;
      if (!main) departed = true;
      else if (departed) { release(); return; }
      if (timer) { clearTimeout(timer); timer = undefined; }
      if (!hasContent(input.state.getSnapshot())) {
        // Native submission settlement may restore a failed optimistic draft in this turn.
        timer = setTimeout(() => { timer = undefined; if (!hasContent(input.state.getSnapshot())) release(); }, 0);
      }
    };
    this.held.set(sessionId, release);
    stops.push(this.ctx.sessions.list.subscribe(check), input.state.subscribe(check), binding.session.subscribe(check));
    return release;
  }
  dispose(): void { this.disposed = true; for (const release of [...this.held.values()]) release(); }
}
