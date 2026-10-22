import { lstat, readdir, realpath } from 'node:fs/promises'
import { basename, dirname, isAbsolute, join, relative, resolve } from 'node:path'
import type { OperationRecord } from './operation-store'
import type { InstallMove } from '../services/install-transaction'
import { readInstalledSkillMetadata } from '../services/installed-skill-metadata'

export async function verifiedBackupPath(record: OperationRecord, move: InstallMove): Promise<string> {
  const root = dirname(move.canonicalSkillDir)
  const name = basename(move.to)
  const suffix = name.slice(`${record.operation.slug}.skillhub-backup-`.length)
  if (!record.operation.targetPaths?.includes(move.canonicalSkillDir) || move.backupDir !== move.to
    || !isAbsolute(move.to) || resolve(move.to) !== move.to || !isAbsolute(move.from)
    || basename(move.from) !== record.operation.slug || dirname(move.from) !== dirname(move.to)
    || !name.startsWith(`${record.operation.slug}.skillhub-backup-`)
    || !/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(suffix)
    || await realpath(dirname(move.to)) !== root) throw new Error('Backup path does not match the recorded target')
  return join(root, name)
}

export async function assertOwnedRegularTree(path: string): Promise<void> {
  const authoredMetadataPaths: string[] = []
  await inspectTree(path, path, authoredMetadataPaths)
  if (!authoredMetadataPaths.length) return
  const metadata = await readInstalledSkillMetadata(path)
  const files = metadata.status === 'valid' ? metadata.metadata.files : undefined
  if (authoredMetadataPaths.some(entry => !files || !(entry in files))) {
    throw new Error('Untracked recovery metadata content')
  }
}

async function inspectTree(path: string, root: string, authoredMetadataPaths: string[]): Promise<void> {
  const info = await lstat(path)
  if (info.isSymbolicLink() || (!info.isDirectory() && !info.isFile())
    || (process.getuid && info.uid !== process.getuid())) throw new Error('Unsafe recovery path')
  const local = relative(root, path).split('\\').join('/')
  if (!info.isDirectory()) {
    // Authored files are permitted only when the installed baseline records them.
    if (local.split('/').includes('.skillhub') && local !== '.skillhub/metadata.json') authoredMetadataPaths.push(local)
    return
  }
  const names = await readdir(path)
  if (!names.length && local.split('/').includes('.skillhub') && local !== '.skillhub') {
    throw new Error('Untracked recovery metadata content')
  }
  for (const name of names) await inspectTree(join(path, name), root, authoredMetadataPaths)
}
