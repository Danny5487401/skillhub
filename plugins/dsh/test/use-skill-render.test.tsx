import { expect, test } from 'bun:test';
import { renderToStaticMarkup } from 'react-dom/server';
import { SkillCaseChoices, SkillUseController } from '../src/client/use-skill.js';
import type { InputState } from '@deepseek-ai/dsh-client-ui-conversation/client';

async function panel(items: { id: string; title: string; prompt: string }[]) {
  const state: InputState = { draft: '', draftRev: 0, phase: 'plain', attachmentIds: [], occurrences: [], queue: [] };
  const actions = { captureInsertion: () => ({ start: 0, end: 0, draftRev: 0 }), insertText: () => true };
  const controller = new SkillUseController({
    capture: () => ({ actions, input: { state: { getSnapshot: () => state }, focus() {} }, assertCurrent() {} }),
    revision: async () => 'source', reveal() {},
    prepare: async () => ({ ok: true, value: { status: 'ready', name: 'native-name', version: '1', scope: 'project',
      examples: { state: 'ready', digest: 'digest', defaultLocale: 'zh-CN', items: { 'zh-CN': items } } } }),
  });
  controller.bind('session', actions);
  const pending = controller.services('session').useSkill('source', 'item', new AbortController().signal).catch(() => {});
  await Bun.sleep(0);
  return { controller, close: async () => { controller.dispose(); await pending; } };
}
test('inline cases render contextual header, both task previews and persistent actions outside the task list', async () => {
  const f = await panel([{ id: 'summary', title: '整理会议', prompt: '请使用 {{skill}} 整理会议。' }, { id: 'compare', title: '对比方案', prompt: '使用 {{skill}} 比较方案。' }]);
  try {
    const html = renderToStaticMarkup(<SkillCaseChoices controller={f.controller} sessionId="session" />);
    expect(html).toContain('aria-label="技能任务案例"'); expect(html).toContain('整理会议'); expect(html).toContain('对比方案');
    expect(html).toContain('请使用 /native-name 整理会议。'); expect(html).not.toContain('{{skill}}');
    expect(html).toMatch(/<header>.*aria-label="关闭案例选择"/u);
    // Long lists scroll independently; actions must not become list items.
    expect(html).toMatch(/<\/div><footer>.*自定义任务.*填入输入框.*<\/footer>/u);
    expect(renderToStaticMarkup(<SkillCaseChoices controller={f.controller} sessionId="another-session" />)).toBe('');
  } finally { await f.close(); }
});
test('long multilingual author cases remain plain text without author markup or added executable controls', async () => {
  const f = await panel(Array.from({ length: 20 }, (_, i) => ({ id: `task-${i}`, title: `<script>任务${i}</script>`, prompt: '<img src=x onerror=alert(1)> {{skill}} '.repeat(8) })));
  try {
    const html = renderToStaticMarkup(<SkillCaseChoices controller={f.controller} sessionId="session" />);
    expect((html.match(/class="astron-sh-case"/gu) ?? []).length).toBe(20);
    expect(html).toContain('&lt;script&gt;任务19&lt;/script&gt;'); expect(html).not.toContain('<script>'); expect(html).not.toContain('<img');
    expect((html.match(/<footer>/gu) ?? []).length).toBe(1); expect(html).toContain('自定义任务'); expect(html).toContain('填入输入框');
  } finally { await f.close(); }
});


test('twenty maximum-length titles and prompts retain complete text for accessibility and selecting the final case', async () => {
  const items = Array.from({ length: 20 }, (_, i) => ({ id: `long-${i}`, title: (i % 2 ? '极长中文案例标题用于检查窄屏排版' : 'VeryLongUnbrokenExampleTitle').repeat(8).slice(0, 80), prompt: `最后选择${i}：{{skill}} ` + '材料'.repeat(1900) }));
  const f = await panel(items);
  try {
    const html = renderToStaticMarkup(<SkillCaseChoices controller={f.controller} sessionId="session" />);
    expect((html.match(/class="astron-sh-case"/gu) ?? []).length).toBe(20);
    expect(html).toContain(items[0]!.title); expect(html).toContain(items[19]!.title);
    expect(html).toContain('最后选择19：/native-name');
    // CSS truncates presentation only; the full last prompt remains available to the choice controller.
    expect(f.controller.casePanel()?.items[19]).toEqual(items[19]!);
    f.controller.casePanel()!.finish({ locale: 'zh-CN', id: 'long-19' });
  } finally { await f.close(); }
});
