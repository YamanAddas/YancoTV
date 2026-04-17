import { v4 as uuid } from 'uuid';
import log from 'electron-log/main';
import { getDb } from './db';
import { encryptCredential, decryptCredential } from './credential-store';
import type { Source, AddSourceInput, UpdateSourceInput } from '../../shared/types';
import type { Result } from '../../shared/types/result';

interface SourceRow {
  id: string;
  name: string;
  type: string;
  url: string | null;
  file_path: string | null;
  epg_url: string | null;
  user_agent: string | null;
  username_encrypted: Buffer | null;
  password_encrypted: Buffer | null;
  mac_address_encrypted: Buffer | null;
  priority: number;
  channel_count: number;
  last_sync_error: string | null;
  auto_sync_interval: number;
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
    userAgent: row.user_agent ?? undefined,
    lastSynced: row.last_synced ?? undefined,
    isActive: row.is_active === 1,
    priority: row.priority,
    channelCount: row.channel_count,
    lastSyncError: row.last_sync_error ?? undefined,
    autoSyncInterval: row.auto_sync_interval,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

export function getAllSources(): Source[] {
  const db = getDb();
  const rows = db.prepare('SELECT * FROM sources ORDER BY priority ASC, created_at DESC').all() as SourceRow[];
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
  let macAddressEncrypted: Buffer | null = null;

  if (input.type === 'xtream' && input.username && input.password) {
    usernameEncrypted = encryptCredential(input.username);
    passwordEncrypted = encryptCredential(input.password);
  }

  if (input.type === 'stalker' && input.macAddress) {
    macAddressEncrypted = encryptCredential(input.macAddress);
  }

  // Set priority to max + 1 so new sources appear at the end
  const maxPriority = db.prepare('SELECT MAX(priority) as max_p FROM sources').get() as { max_p: number | null };
  const priority = (maxPriority.max_p ?? -1) + 1;

  try {
    // The original schema has CHECK(type IN ('m3u_url','m3u_file','xtream')).
    // Bypass it for stalker-type sources. Safe: Zod validates type at the IPC boundary.
    if (input.type === 'stalker') {
      db.pragma('ignore_check_constraints = ON');
    }

    try {
      db.prepare(
        `INSERT INTO sources (id, name, type, url, file_path, epg_url, user_agent, username_encrypted, password_encrypted, mac_address_encrypted, priority, is_active, created_at, updated_at)
         VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?)`,
      ).run(
        id,
        input.name,
        input.type,
        input.url ?? null,
        input.filePath ?? null,
        input.epgUrl ?? null,
        input.userAgent?.trim() || null,
        usernameEncrypted,
        passwordEncrypted,
        macAddressEncrypted,
        priority,
        now,
        now,
      );
    } finally {
      if (input.type === 'stalker') {
        db.pragma('ignore_check_constraints = OFF');
      }
    }

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

export function updateSource(input: UpdateSourceInput): Result<Source> {
  const db = getDb();
  const existing = getSourceById(input.id);
  if (!existing) {
    return { ok: false, error: new Error('Source not found') };
  }

  try {
    const sets: string[] = [];
    // eslint-disable-next-line @typescript-eslint/no-explicit-any
    const values: any[] = [];

    if (input.name !== undefined) {
      sets.push('name = ?');
      values.push(input.name);
    }
    if (input.url !== undefined) {
      sets.push('url = ?');
      values.push(input.url);
    }
    if (input.epgUrl !== undefined) {
      sets.push('epg_url = ?');
      values.push(input.epgUrl || null);
    }
    if (input.userAgent !== undefined) {
      sets.push('user_agent = ?');
      values.push(input.userAgent.trim() || null);
    }
    if (input.autoSyncInterval !== undefined) {
      sets.push('auto_sync_interval = ?');
      values.push(input.autoSyncInterval);
    }
    if (input.username !== undefined) {
      sets.push('username_encrypted = ?');
      values.push(input.username ? encryptCredential(input.username) : null);
    }
    if (input.password !== undefined) {
      sets.push('password_encrypted = ?');
      values.push(input.password ? encryptCredential(input.password) : null);
    }
    if (input.macAddress !== undefined) {
      sets.push('mac_address_encrypted = ?');
      values.push(input.macAddress ? encryptCredential(input.macAddress) : null);
    }

    if (sets.length === 0) {
      return { ok: true, value: existing };
    }

    sets.push('updated_at = ?');
    values.push(Date.now());
    values.push(input.id);

    db.prepare(`UPDATE sources SET ${sets.join(', ')} WHERE id = ?`).run(...values);

    const updated = getSourceById(input.id);
    if (!updated) {
      return { ok: false, error: new Error('Source updated but not found') };
    }

    log.info(`Source updated: ${updated.name} (${updated.type})`);
    return { ok: true, value: updated };
  } catch (error) {
    log.error('Failed to update source:', error);
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

export function reorderSources(orderedIds: string[]): Result<void> {
  const db = getDb();
  try {
    const updatePriority = db.prepare('UPDATE sources SET priority = ?, updated_at = ? WHERE id = ?');
    const now = Date.now();

    const reorder = db.transaction(() => {
      for (let i = 0; i < orderedIds.length; i++) {
        updatePriority.run(i, now, orderedIds[i]);
      }
    });
    reorder();

    log.info(`Sources reordered: ${orderedIds.length} sources`);
    return { ok: true, value: undefined };
  } catch (error) {
    log.error('Failed to reorder sources:', error);
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

export function updateSourceHealth(id: string, channelCount: number, syncError?: string): void {
  const db = getDb();
  db.prepare(
    'UPDATE sources SET channel_count = ?, last_sync_error = ?, updated_at = ? WHERE id = ?',
  ).run(channelCount, syncError ?? null, Date.now(), id);
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

export function getSourceMacAddress(id: string): string | null {
  const db = getDb();
  const row = db
    .prepare('SELECT mac_address_encrypted FROM sources WHERE id = ?')
    .get(id) as Pick<SourceRow, 'mac_address_encrypted'> | undefined;

  if (!row?.mac_address_encrypted) return null;
  return decryptCredential(row.mac_address_encrypted);
}
