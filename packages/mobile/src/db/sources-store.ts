import { getDb } from './db';

/**
 * Mobile source persistence — SQL layer only. The Zustand wrapper in
 * src/stores/sources-store.ts owns in-memory shape + sync orchestration.
 *
 * Credentials are stored as plaintext in the *_encrypted BLOB columns.
 * That matches the desktop column shape but WITHOUT the safeStorage
 * wrapping — real encryption arrives in M7 when react-native-keychain is
 * wired up and credentials migrate to the Android Keystore. Until then
 * we're no worse off than the previous AsyncStorage path, which also
 * stored credentials in plaintext.
 */

export type MobileSourceType = 'm3u_url' | 'xtream' | 'stalker';

export interface StoredSource {
  id: string;
  name: string;
  type: MobileSourceType;
  url: string;
  epgUrl?: string;
  lastSynced?: number;
  lastSyncError?: string;
  channelCount: number;
  priority: number;
  createdAt: number;
  updatedAt: number;
}

export interface StoredCredentials {
  username?: string;
  password?: string;
  macAddress?: string;
}

interface SourceRow {
  id: string;
  name: string;
  type: string;
  url: string | null;
  epg_url: string | null;
  username_encrypted: unknown;
  password_encrypted: unknown;
  mac_address_encrypted: unknown;
  priority: number;
  channel_count: number;
  last_sync_error: string | null;
  last_synced: number | null;
  is_active: number;
  created_at: number;
  updated_at: number;
}

function bytesToString(v: unknown): string | undefined {
  if (v == null) return undefined;
  if (typeof v === 'string') return v;
  // op-sqlite may hand BLOBs back as ArrayBuffer / Uint8Array.
  if (v instanceof Uint8Array) return new TextDecoder().decode(v);
  if (v instanceof ArrayBuffer) return new TextDecoder().decode(new Uint8Array(v));
  return undefined;
}

function rowToSource(row: SourceRow): StoredSource {
  return {
    id: row.id,
    name: row.name,
    type: row.type as MobileSourceType,
    url: row.url ?? '',
    epgUrl: row.epg_url ?? undefined,
    lastSynced: row.last_synced ?? undefined,
    lastSyncError: row.last_sync_error ?? undefined,
    channelCount: row.channel_count,
    priority: row.priority,
    createdAt: row.created_at,
    updatedAt: row.updated_at,
  };
}

export async function getAllSources(): Promise<StoredSource[]> {
  const db = getDb();
  const res = await db.execute(
    'SELECT * FROM sources ORDER BY priority ASC, created_at DESC',
  );
  const rows = (res.rows ?? []) as unknown as SourceRow[];
  return rows.map(rowToSource);
}

export async function getSourceById(
  id: string,
): Promise<StoredSource | null> {
  const db = getDb();
  const res = await db.execute('SELECT * FROM sources WHERE id = ?', [id]);
  const row = (res.rows ?? [])[0] as unknown as SourceRow | undefined;
  return row ? rowToSource(row) : null;
}

/** Read the credentials for a single source. Caller handles "missing" cases. */
export async function getSourceCredentials(
  id: string,
): Promise<StoredCredentials> {
  const db = getDb();
  const res = await db.execute(
    'SELECT username_encrypted, password_encrypted, mac_address_encrypted FROM sources WHERE id = ?',
    [id],
  );
  const row = (res.rows ?? [])[0] as unknown as
    | {
        username_encrypted: unknown;
        password_encrypted: unknown;
        mac_address_encrypted: unknown;
      }
    | undefined;
  if (!row) return {};
  return {
    username: bytesToString(row.username_encrypted),
    password: bytesToString(row.password_encrypted),
    macAddress: bytesToString(row.mac_address_encrypted),
  };
}

export interface InsertSourceInput {
  id: string;
  name: string;
  type: MobileSourceType;
  url: string;
  epgUrl?: string;
  username?: string;
  password?: string;
  macAddress?: string;
}

/**
 * Insert a new source row.
 *
 * SQLite CHECK(type IN ('m3u_url','m3u_file','xtream')) was set in
 * migration 001 before stalker support existed. The desktop flips
 * PRAGMA ignore_check_constraints=ON around the insert; op-sqlite
 * supports the same pragma, so mirror the workaround here.
 */
export async function insertSource(input: InsertSourceInput): Promise<void> {
  const db = getDb();
  const now = Date.now();

  const maxRes = await db.execute('SELECT MAX(priority) AS max_p FROM sources');
  const maxRow = (maxRes.rows ?? [])[0] as unknown as { max_p: number | null } | undefined;
  const priority = (maxRow?.max_p ?? -1) + 1;

  if (input.type === 'stalker') {
    await db.execute('PRAGMA ignore_check_constraints = ON');
  }
  try {
    await db.execute(
      `INSERT INTO sources
         (id, name, type, url, epg_url,
          username_encrypted, password_encrypted, mac_address_encrypted,
          priority, channel_count, is_active, auto_sync_interval,
          last_synced, last_sync_error,
          created_at, updated_at)
       VALUES (?, ?, ?, ?, ?,
               ?, ?, ?,
               ?, 0, 1, 0,
               NULL, NULL,
               ?, ?)`,
      [
        input.id,
        input.name,
        input.type,
        input.url,
        input.epgUrl ?? null,
        input.username ?? null,
        input.password ?? null,
        input.macAddress ?? null,
        priority,
        now,
        now,
      ],
    );
  } finally {
    if (input.type === 'stalker') {
      await db.execute('PRAGMA ignore_check_constraints = OFF');
    }
  }
}

export interface UpdateSourceSyncInput {
  lastSynced?: number | null;
  channelCount?: number;
  lastSyncError?: string | null;
}

export async function updateSourceSync(
  id: string,
  patch: UpdateSourceSyncInput,
): Promise<void> {
  const db = getDb();
  // Build the SET list + params dynamically. Only touch columns the caller
  // actually specified — this matters for `lastSyncError`, where `null`
  // means "clear" and `undefined` means "leave as-is".
  const fragments: string[] = [];
  const params: Array<string | number | null> = [];
  if (patch.lastSynced !== undefined) {
    fragments.push('last_synced = ?');
    params.push(patch.lastSynced);
  }
  if (patch.channelCount !== undefined) {
    fragments.push('channel_count = ?');
    params.push(patch.channelCount);
  }
  if (patch.lastSyncError !== undefined) {
    fragments.push('last_sync_error = ?');
    params.push(patch.lastSyncError);
  }
  fragments.push('updated_at = ?');
  params.push(Date.now());
  params.push(id);

  await db.execute(
    `UPDATE sources SET ${fragments.join(', ')} WHERE id = ?`,
    params,
  );
}

/**
 * Delete a source. ON DELETE CASCADE from the content/episodes FKs clears
 * the child rows automatically; FTS triggers wipe the index. We still
 * belt-and-brace-clear the FTS rows explicitly because earlier migrations
 * on older devices may have out-of-sync trigger state.
 */
export async function deleteSource(id: string): Promise<void> {
  const db = getDb();
  await db.transaction(async (tx) => {
    await tx.execute(
      'DELETE FROM content_fts WHERE content_id IN (SELECT id FROM content WHERE source_id = ?)',
      [id],
    );
    await tx.execute('DELETE FROM sources WHERE id = ?', [id]);
  });
}
