import { v4 as uuid } from 'uuid';
import log from 'electron-log/main';
import { getDb } from './db';
import { encryptCredential, decryptCredential } from './credential-store';
import type { Source, AddSourceInput } from '../../shared/types';
import type { Result } from '../../shared/types/result';

interface SourceRow {
  id: string;
  name: string;
  type: string;
  url: string | null;
  file_path: string | null;
  epg_url: string | null;
  username_encrypted: Buffer | null;
  password_encrypted: Buffer | null;
  last_synced: number | null;
  is_active: number;
  created_at: number;
  updated_at: number;
}

function rowToSource(row: SourceRow): Source {
  return {
    id: row.id,
    name: row.name,
    type: row.type as Source['type'],
    url: row.url ?? undefined,
    filePath: row.file_path ?? undefined,
    epgUrl: row.epg_url ?? undefined,
    lastSynced: row.last_synced ?? undefined,
    isActive: row.is_active === 1,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

export function getAllSources(): Source[] {
  const db = getDb();
  const rows = db.prepare('SELECT * FROM sources ORDER BY created_at DESC').all() as SourceRow[];
  return rows.map(rowToSource);
}

export function getSourceById(id: string): Source | null {
  const db = getDb();
  const row = db.prepare('SELECT * FROM sources WHERE id = ?').get(id) as SourceRow | undefined;
  return row ? rowToSource(row) : null;
}

export function addSource(input: AddSourceInput): Result<Source> {
  const db = getDb();
  const id = uuid();
  const now = Date.now();

  let usernameEncrypted: Buffer | null = null;
  let passwordEncrypted: Buffer | null = null;

  if (input.type === 'xtream' && input.username && input.password) {
    usernameEncrypted = encryptCredential(input.username);
    passwordEncrypted = encryptCredential(input.password);
  }

  try {
    db.prepare(
      `INSERT INTO sources (id, name, type, url, file_path, epg_url, username_encrypted, password_encrypted, is_active, created_at, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)`,
    ).run(
      id,
      input.name,
      input.type,
      input.url ?? null,
      input.filePath ?? null,
      input.epgUrl ?? null,
      usernameEncrypted,
      passwordEncrypted,
      now,
      now,
    );

    const source = getSourceById(id);
    if (!source) {
      return { ok: false, error: new Error('Source created but not found') };
    }

    log.info(`Source added: ${source.name} (${source.type})`);
    return { ok: true, value: source };
  } catch (error) {
    log.error('Failed to add source:', error);
    return { ok: false, error: error instanceof Error ? error : new Error(String(error)) };
  }
}

export function removeSource(id: string): Result<void> {
  const db = getDb();
  try {
    const result = db.prepare('DELETE FROM sources WHERE id = ?').run(id);
    if (result.changes === 0) {
      return { ok: false, error: new Error('Source not found') };
    }
    log.info(`Source removed: ${id}`);
    return { ok: true, value: undefined };
  } catch (error) {
    log.error('Failed to remove source:', error);
    return { ok: false, error: error instanceof Error ? error : new Error(String(error)) };
  }
}

export function updateSourceSyncTime(id: string): void {
  const db = getDb();
  db.prepare('UPDATE sources SET last_synced = ?, updated_at = ? WHERE id = ?').run(
    Date.now(),
    Date.now(),
    id,
  );
}

export function updateSourceEpgUrl(id: string, epgUrl: string): void {
  const db = getDb();
  db.prepare('UPDATE sources SET epg_url = ?, updated_at = ? WHERE id = ? AND (epg_url IS NULL OR epg_url = ?)').run(
    epgUrl,
    Date.now(),
    id,
    '', // Only update if currently empty — don't overwrite user's manual setting
  );
}

export function getSourceCredentials(id: string): { username: string; password: string } | null {
  const db = getDb();
  const row = db
    .prepare('SELECT username_encrypted, password_encrypted FROM sources WHERE id = ?')
    .get(id) as Pick<SourceRow, 'username_encrypted' | 'password_encrypted'> | undefined;

  if (!row?.username_encrypted || !row?.password_encrypted) return null;

  return {
    username: decryptCredential(row.username_encrypted),
    password: decryptCredential(row.password_encrypted),
  };
}
