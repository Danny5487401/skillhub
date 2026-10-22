import type { Context } from '@deepseek-ai/cordis';
import { FileSystemSkillProvider } from '@deepseek-ai/dsh-skill-filesystem';
import { join } from 'node:path';
import { isDeepStrictEqual } from 'node:util';

export type LoadedSkill = NonNullable<Awaited<ReturnType<FileSystemSkillProvider['get']>>>;

export type DshSkillObservation = { status: 'ready'; skill: LoadedSkill }
  | { status: 'unavailable' | 'shadowed' };

/** Load the exact local package and compare it with the current session's registry winner. */
export async function inspectDshSkill(ctx: Context, directory: string, signal: AbortSignal,
  lookup: (name: string) => Promise<LoadedSkill | undefined>): Promise<DshSkillObservation> {
  signal.throwIfAborted();
  const provider = new FileSystemSkillProvider(ctx, { signal, invalidate: () => {} }, {
    providerName: 'skillhub-discovery-check', includeDefaultRoots: false, customSkillDirs: [directory], watch: false,
  });
  try {
    const listed = await provider.list({ signal });
    if (!Array.isArray(listed) && !listed.complete) return { status: 'unavailable' };
    const candidates = Array.isArray(listed) ? listed : listed.candidates;
    const candidate = candidates.find(item => item.path === join(directory, 'SKILL.md'));
    if (!candidate) return { status: 'unavailable' };
    const expected = await provider.get(candidate, { signal });
    if (!expected) return { status: 'unavailable' };
    const actual = await lookup(expected.name);
    signal.throwIfAborted();
    if (!actual) return { status: 'unavailable' };
    if (!sameDshSkill(actual, expected, directory)) return { status: 'shadowed' };
    return { status: 'ready', skill: actual };
  } finally { await provider.dispose(); }
}

/** Preserve the operation observer's boolean contract without registering another provider. */
export async function observeDshInstallation(ctx: Context, paths: readonly string[], signal: AbortSignal,
  lookup: (name: string) => Promise<LoadedSkill | undefined>): Promise<boolean> {
  for (const directory of paths) {
    if ((await inspectDshSkill(ctx, directory, signal, lookup)).status !== 'ready') return false;
  }
  return paths.length > 0;
}

/** Compare the winner again after filesystem inspection, without loading another provider. */
export function sameDshSkill(actual: LoadedSkill, expected: LoadedSkill, directory: string): boolean {
  return actual.resourceBase?.kind === 'directory' && actual.resourceBase.path === directory
    && actual.path === expected.path && actual.name === expected.name && actual.content === expected.content
    && actual.description === expected.description && actual.whenToUse === expected.whenToUse
    && isDeepStrictEqual(actual.invocation, expected.invocation)
    && isDeepStrictEqual(actual.metadata, expected.metadata);
}
