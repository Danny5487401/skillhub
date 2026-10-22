import { expect, test } from 'bun:test';
import { Context, symbols } from '@deepseek-ai/cordis';
import { FileSystemSkillProvider } from '@deepseek-ai/dsh-skill-filesystem';
import { readSkillExamples } from '../../../cli/src/services/skill-examples.js';
import { randomBytes, createHash } from 'node:crypto';
import { lstat, mkdir, mkdtemp, readFile, readdir, realpath, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import SkillHubService from '../src/host/index.js';
import type { LoadedSkill } from '../src/host/skill-discovery.js';
import type { InstalledProjectionItem, RemovalBaseline } from '../src/host/generated/native-contract.js';
import type { SkillUsePreparation } from '../src/host/types.js';

async function fixture(run: (f: {
  use(signal?: AbortSignal, key?: string): Promise<SkillUsePreparation>; key: string; directory: string;
  setLookup(callback: (loaded: LoadedSkill) => Promise<LoadedSkill | undefined>): void;
  change(kind: string): void; observations(): number;
}) => Promise<void>, frontmatter = '', examples?: string) {
  const root = await realpath(await mkdtemp(join(tmpdir(), 'skillhub-use-')));
  const previousHome = process.env.DSH_HOME;
  const home = join(root, 'home'); const cwd = join(root, 'project');
  const directory = join(home, 'skills', 'market-slug');
  await mkdir(directory, { recursive: true }); await mkdir(cwd); process.env.DSH_HOME = home;
  const body = `---\nname: actual-name\ndescription: Prepare documents\n${frontmatter}---\nUse references/note.md.`;
  await writeFile(join(directory, 'SKILL.md'), body);
  await mkdir(join(directory, 'references')); await writeFile(join(directory, 'references/note.md'), 'original');
  if (examples !== undefined) { await mkdir(join(directory, '.skillhub')); await writeFile(join(directory, '.skillhub/examples.json'), examples); }
  const ctx = new Context(); const controller = new AbortController();
  const provider = new FileSystemSkillProvider(ctx, { signal: controller.signal, invalidate: () => {} }, {
    providerName: 'fixture', includeDefaultRoots: false, customSkillDirs: [directory], watch: false,
  });
  try {
    const listed = await provider.list({ signal: controller.signal });
    const candidates = Array.isArray(listed) ? listed : listed.candidates;
    const loaded = await provider.get(candidates[0]!, { signal: controller.signal });
    if (!loaded) throw new Error('Missing fixture skill');
    let lookup = async (skill: LoadedSkill): Promise<LoadedSkill | undefined> => skill;
    let revision = 'source'; let observations = 0; let pendingOperations = 0; let shared = false;
    const fs = { processPathFromHostPath: (path: string) => path,
      resolve: async (path: string) => ({ displayPath: path }), processPath: (target: { displayPath: string }) => target.displayPath,
      listDir: async (target: { displayPath: string }) => (await readdir(target.displayPath, { withFileTypes: true })).map(entry => ({
        name: entry.name, type: entry.isDirectory() ? 'directory' : 'file', target: { displayPath: join(target.displayPath, entry.name) },
      })),
      stat: async (target: { displayPath: string }) => ({ type: (await lstat(target.displayPath)).isDirectory() ? 'directory' : 'file' }),
      readText: (target: { displayPath: string }) => readFile(target.displayPath, 'utf8'),
    };
    let currentFs = fs;
    const session = { id: 'session-a', header: { cwd } };
    const agent = { session, ctx };
    const nativeGet = ctx.get.bind(ctx);
    ctx.get = ((name: string) => name === 'fs' ? currentFs : nativeGet(name as never)) as typeof ctx.get;
    const skills = { get: async (name: string, options: { cwd: string; scope: unknown; signal: AbortSignal }) => {
      expect(name).toBe('actual-name'); expect(options.cwd).toBe(cwd); expect(options.scope).toBe(agent);
      options.signal.throwIfAborted(); observations++; return lookup(loaded);
    } };
    let currentSkills: (typeof skills & { [symbols.original]?: object }) | undefined = skills;
    let currentAgent: typeof agent | undefined = agent;
    const item: InstalledProjectionItem = { namespace: 'demo', slug: 'market-slug', version: '1.2.0', scope: 'user',
      state: 'present', fingerprint: 'fingerprint', targetPath: directory };
    const items = [item];
    const baseline: RemovalBaseline = { status: 'ready', kind: 'uninstall', inventoryItem: {
      registry: 'https://registry.example', namespace: item.namespace, slug: item.slug, version: item.version,
      fingerprint: item.fingerprint, targets: [{ agent: 'dsh', rootDir: join(home, 'skills'), installDir: directory, installedAt: 'now' }],
    }, target: { scope: 'user', root: join(home, 'skills'), path: directory, files: examples === undefined ? {} : { '.skillhub/examples.json': createHash('sha256').update(examples).digest('hex') }, metadata: {
      registry: 'https://registry.example', namespace: item.namespace, slug: item.slug, version: item.version,
    } } };
    const runtime = { getStatus: () => ({ state: 'ready', pendingOperations }),
      readSkillExamples,
      listInstalled: async () => ({ items, complete: true, issues: [] }),
      inspectRemoval: async (): Promise<RemovalBaseline> => {
        if (shared) return { status: 'blocked', reason: 'shared-target' };
        // Model the existing baseline's all-resource checks; real bundled core is covered by native acceptance.
        if (await readFile(join(directory, 'SKILL.md'), 'utf8') !== body
          || await readFile(join(directory, 'references/note.md'), 'utf8') !== 'original'
          || (examples !== undefined && await readFile(join(directory, '.skillhub/examples.json'), 'utf8') !== examples)) {
          return { status: 'blocked', reason: 'local-changes' };
        }
        return structuredClone(baseline);
      } };
    const service = Object.assign(Object.create(SkillHubService.prototype), {
      lifetime: controller, installedReferenceKey: randomBytes(32), localRuntime: runtime,
      ctx: { invocation: { request: { args: { agentId: 'session-a' } } }, get: (name: string) =>
        name === 'agents' ? { get: () => currentAgent } : name === 'skills' ? currentSkills : undefined },
      assertSource: (value: string) => { if (value !== revision) throw new Error('source changed'); },
      getContext: () => ({ configured: true, registryUrl: 'https://registry.example' }),
    });
    const key = (await service.listInstalled('source', new AbortController().signal)).items[0].itemKey;
    await run({ use: (signal = new AbortController().signal, selected = key) => service.prepareSkillUse('source', selected, signal),
      key, directory, observations: () => observations, setLookup: value => { lookup = value; }, change: kind => {
        if (kind === 'source') revision = 'other';
        if (kind === 'session') session.id = 'session-b';
        if (kind === 'cwd') session.header.cwd = home;
        if (kind === 'agent') currentAgent = { ...agent };
        if (kind === 'fs') currentFs = { ...fs };
        if (kind === 'skills') currentSkills = { ...skills };
        if (kind === 'proxy') currentSkills = { ...skills, [symbols.original]: skills };
        if (kind === 'missing-skills') currentSkills = undefined;
        if (kind === 'runtime') service.localRuntime = { ...runtime };
        if (kind === 'record') items.length = 0;
        if (kind === 'version') item.version = '2.0.0';
        if (kind === 'shared') shared = true;
        if (kind === 'pending') pendingOperations = 1;
        if (kind === 'dispose') controller.abort();
      } });
  } finally {
    await provider.dispose();
    if (previousHome === undefined) delete process.env.DSH_HOME; else process.env.DSH_HOME = previousHome;
    await rm(root, { recursive: true, force: true });
  }
}

test('use returns actual SKILL.md name, never the market slug or local paths', async () => {
  await fixture(async f => {
    expect(await f.use()).toEqual({ status: 'ready', name: 'actual-name', version: '1.2.0', scope: 'user', examples: { state: 'none' } });
    expect(f.observations()).toBe(2);
    f.change('proxy'); expect((await f.use()).status).toBe('ready');
  });
});

test('unavailable and overridden native winners cannot authorize an invocation', async () => {
  await fixture(async f => {
    f.setLookup(async () => undefined);
    expect(await f.use()).toEqual({ status: 'blocked', reason: 'skill-unavailable' });
    f.setLookup(async loaded => ({ ...loaded, resourceBase: { kind: 'directory', path: '/other' } }));
    expect(await f.use()).toEqual({ status: 'blocked', reason: 'skill-shadowed' });
    f.setLookup(async loaded => ({ ...loaded, content: 'modified winner' }));
    expect(await f.use()).toEqual({ status: 'blocked', reason: 'skill-shadowed' });
  });
});

test('actual user-invocable policy blocks handoff even when model invocation is allowed', async () => {
  await fixture(async f => {
    expect(await f.use()).toEqual({ status: 'blocked', reason: 'user-invocation-disabled' });
  }, 'user-invocable: false\n');
});

for (const filename of ['SKILL.md', 'references/note.md']) {
  test(`changed ${filename} is blocked before discovery and after asynchronous lookup`, async () => {
    await fixture(async f => {
      f.setLookup(async loaded => { await writeFile(join(f.directory, filename), 'changed'); return loaded; });
      expect(await f.use()).toEqual({ status: 'blocked', reason: 'files-changed' });
      expect(await f.use()).toEqual({ status: 'blocked', reason: 'files-changed' });
      expect(f.observations()).toBe(1);
    });
  });
}

for (const kind of ['source', 'session', 'cwd', 'agent', 'fs', 'skills', 'runtime', 'record', 'version', 'dispose']) {
  test(`use rejects ${kind} changing during native discovery`, async () => {
    await fixture(async f => {
      f.setLookup(async loaded => { f.change(kind); return loaded; });
      await expect(f.use()).rejects.toThrow();
    });
  });
}

for (const [kind, reason] of [['shared', 'shared-installation'], ['pending', 'operation-pending'], ['missing-skills', 'host-unavailable']] as const) {
  test(`use blocks ${kind} without reading a native skill`, async () => {
    await fixture(async f => {
      f.change(kind); expect(await f.use()).toEqual({ status: 'blocked', reason }); expect(f.observations()).toBe(0);
    });
  });
}

test('stale and forged references and aborted requests never load a skill', async () => {
  await fixture(async f => {
    await expect(f.use(AbortSignal.abort())).rejects.toThrow();
    await expect(f.use(undefined, `${f.key}!`)).rejects.toThrow();
    f.change('record'); await expect(f.use()).rejects.toThrow(); expect(f.observations()).toBe(0);
  });
});

test('winner is checked again after the second filesystem baseline', async () => {
  await fixture(async f => {
    let lookups = 0;
    f.setLookup(async loaded => ++lookups === 1 ? loaded : { ...loaded,
      resourceBase: { kind: 'directory', path: '/replacement' } });
    expect(await f.use()).toEqual({ status: 'blocked', reason: 'skill-shadowed' });
    expect(lookups).toBe(2);
  });
});

test('deleted local resources never produce a ready handoff', async () => {
  await fixture(async f => {
    f.setLookup(async loaded => { await rm(join(f.directory, 'references/note.md')); return loaded; });
    expect((await f.use()).status).toBe('blocked');
  });
});

test('late native service replacement and cancellation remain errors at the final lookup', async () => {
  for (const kind of ['skills', 'dispose', 'cwd']) {
    await fixture(async f => {
      let lookups = 0;
      f.setLookup(async loaded => { if (++lookups === 2) f.change(kind); return loaded; });
      await expect(f.use()).rejects.toThrow();
      expect(lookups).toBe(2);
    });
  }
});


test('an operation becoming pending during final discovery blocks a ready handoff', async () => {
  await fixture(async f => {
    let lookups = 0;
    f.setLookup(async loaded => { if (++lookups === 2) f.change('pending'); return loaded; });
    expect(await f.use()).toEqual({ status: 'blocked', reason: 'operation-pending' });
    expect(lookups).toBe(2);
  });
});

const multilingual = JSON.stringify({ schemaVersion: 1, defaultLocale: 'zh-CN', examples: {
  'zh-CN': [{ id: 'summary', title: '会议纪要', prompt: '请使用 {{skill}} 整理会议。' }],
  en: [{ id: 'summary', title: 'Summary', prompt: 'Use {{skill}} to summarize.' }],
} });
test('Host returns exact selected installation examples with raw digest and no path disclosure', async () => {
  await fixture(async f => {
    const prepared = await f.use(); expect(prepared.status).toBe('ready');
    if (prepared.status !== 'ready') throw new Error('must be ready');
    expect(prepared.name).toBe('actual-name'); expect(prepared.examples.state).toBe('ready');
    if (prepared.examples.state !== 'ready') throw new Error('must have examples');
    expect(prepared.examples.digest).toBe(createHash('sha256').update(multilingual).digest('hex'));
    expect(prepared.examples.items.en?.[0]?.title).toBe('Summary');
    expect(JSON.stringify(prepared)).not.toContain(f.directory);
  }, '', multilingual);
});
test('invalid packaged examples remain ordinary protected content and retain custom use', async () => {
  await fixture(async f => {
    const prepared = await f.use(); expect(prepared.status).toBe('ready');
    if (prepared.status === 'ready') expect(prepared.examples).toEqual({ state: 'invalid' });
  }, '', '{not json');
});
test('examples modified during native discovery prevent ready even if valid protocol', async () => {
  await fixture(async f => {
    f.setLookup(async loaded => { await writeFile(join(f.directory, '.skillhub/examples.json'), multilingual + ' '); return loaded; });
    expect(await f.use()).toEqual({ status: 'blocked', reason: 'files-changed' });
  }, '', multilingual);
});

test('examples changed at final winner lookup are blocked by the last content barrier', async () => {
  await fixture(async f => {
    let lookups = 0;
    f.setLookup(async loaded => { if (++lookups === 2) await writeFile(join(f.directory, '.skillhub/examples.json'), multilingual + ' '); return loaded; });
    expect(await f.use()).toEqual({ status: 'blocked', reason: 'files-changed' });
  }, '', multilingual);
});
