import fs from 'fs/promises';
import { v4 as uuid } from 'uuid';
import { app } from 'electron';
import log from 'electron-log/main';
import { getDb } from './db';
import { getAllSources } from './source-manager';
import { decryptCredential, encryptCredential } from './credential-store';
import { getFavorites } from './favorites-store';
import { getAllSettings } from './settings-service';
import {
  getLockedChannelIds,
  getHiddenChannelIds,
  getAllChannelOverrides,
  type ChannelOverride,
} from './parental-service';
import type { Source } from '../../shared/types';

// ---------------------------------------------------------------------------
// Backup Service — export / import user data as a single JSON file
//
// Scope: sources (with decrypted credentials), favorites, watch history,
// settings, parental controls, and group preferences. Content/EPG rows are
// NOT exported — they're re-derived from sources on sync.
//
// Favorites and history reference content IDs that are randomUUIDs generated
// at sync time, so raw IDs don't round-trip across machines. We export the
// (sourceId, streamUrl, title) tuple alongside each reference so the import
// side can re-link by URL after the user re-syncs their sources.
// ---------------------------------------------------------------------------

/** Current backup schema version. Bump when breaking changes are made. */
export const BACKUP_VERSION = 1;

export interface SourceBackup extends Source {
  /** Decrypted username (only present for xtream) */
  username?: string;
  /** Decrypted password (only present for xtream) */
  password?: string;
  /** Decrypted MAC address (only present for stalker) */
  macAddress?: string;
}

export interface FavoriteBackup {
  contentId: string;
  addedAt: number;
  /** Re-link hints for import (IDs regenerate on re-sync) */
  ref: { sourceId: string; streamUrl: string; title: string; tvgId?: string };
}

export interface HistoryBackup {
  id: string;
  contentId: string;
  episodeId: string | null;
  positionSeconds: number;
  durationSeconds: number | null;
  watchedAt: number;
  /** Re-link hints for import */
  ref: { sourceId: string; streamUrl: string; title: string };
}

export interface GroupPrefBackup {
  id: string;
  contentType: string;
  groupKey: string;
  sortOrder: number;
  isHidden: boolean;
  isPinned: boolean;
  customName: string | null;
  createdAt: number;
}

export interface BackupFile {
  version: number;
  appVersion: string;
  exportedAt: number;
  sources: SourceBackup[];
  favorites: FavoriteBackup[];
  history: HistoryBackup[];
  settings: Record<string, string>;
  parental: {
    lockedChannelIds: string[];
    hiddenChannelIds: string[];
    overrides: Record<string, ChannelOverride>;
  };
  groupPreferences: GroupPrefBackup[];
}

// ---------------------------------------------------------------------------
// Collection helpers
// ---------------------------------------------------------------------------

function collectSources(): SourceBackup[] {
  const db = getDb();
  const sources = getAllSources();
  const enriched: SourceBackup[] = [];
  for (const src of sources) {
    const row = db
      .prepare(
        'SELECT username_encrypted, password_encrypted, mac_address_encrypted FROM sources WHERE id = ?',
      )
      .get(src.id) as
      | {
          username_encrypted: Buffer | null;
          password_encrypted: Buffer | null;
          mac_address_encrypted: Buffer | null;
        }
      | undefined;
    const entry: SourceBackup = { ...src };
    if (row) {
      try {
        if (row.username_encrypted) entry.username = decryptCredential(row.username_encrypted);
        if (row.password_encrypted) entry.password = decryptCredential(row.password_encrypted);
        if (row.mac_address_encrypted) entry.macAddress = decryptCredential(row.mac_address_encrypted);
      } catch (err) {
        log.warn(`Failed to decrypt credentials for source ${src.id}:`, err);
      }
    }
    enriched.push(entry);
  }
  return enriched;
}

function collectFavorites(): FavoriteBackup[] {
  const favorites = getFavorites();
  return favorites.map((f) => ({
    contentId: f.content.id,
    addedAt: f.addedAt,
    ref: {
      sourceId: f.content.sourceId,
      streamUrl: f.content.streamUrl,
      title: f.content.title,
      tvgId: f.content.tvgId,
    },
  }));
}

function collectHistory(): HistoryBackup[] {
  const db = getDb();
  const rows = db
    .prepare(
      `SELECT wh.id, wh.content_id, wh.episode_id, wh.position_seconds, wh.duration_seconds, wh.watched_at,
              c.source_id, c.stream_url, c.title
       FROM watch_history wh
       JOIN content c ON c.id = wh.content_id
       ORDER BY wh.watched_at DESC`,
    )
    .all() as Array<{
      id: string;
      content_id: string;
      episode_id: string | null;
      position_seconds: number;
      duration_seconds: number | null;
      watched_at: number;
      source_id: string;
      stream_url: string;
      title: string;
    }>;
  return rows.map((r) => ({
    id: r.id,
    contentId: r.content_id,
    episodeId: r.episode_id,
    positionSeconds: r.position_seconds,
    durationSeconds: r.duration_seconds,
    watchedAt: r.watched_at,
    ref: { sourceId: r.source_id, streamUrl: r.stream_url, title: r.title },
  }));
}

function collectGroupPreferences(): GroupPrefBackup[] {
  const db = getDb();
  const rows = db
    .prepare(
      'SELECT id, content_type, group_key, sort_order, is_hidden, is_pinned, custom_name, created_at FROM group_preferences',
    )
    .all() as Array<{
      id: string;
      content_type: string;
      group_key: string;
      sort_order: number;
      is_hidden: number;
      is_pinned: number;
      custom_name: string | null;
      created_at: number;
    }>;
  return rows.map((r) => ({
    id: r.id,
    contentType: r.content_type,
    groupKey: r.group_key,
    sortOrder: r.sort_order,
    isHidden: r.is_hidden === 1,
    isPinned: r.is_pinned === 1,
    customName: r.custom_name,
    createdAt: r.created_at,
  }));
}

// ---------------------------------------------------------------------------
// Public API
// ---------------------------------------------------------------------------

/** Build an in-memory backup snapshot without writing to disk. */
export function buildBackup(): BackupFile {
  return {
    version: BACKUP_VERSION,
    appVersion: app.getVersion(),
    exportedAt: Date.now(),
    sources: collectSources(),
    favorites: collectFavorites(),
    history: collectHistory(),
    settings: getAllSettings(),
    parental: {
      lockedChannelIds: getLockedChannelIds(),
      hiddenChannelIds: getHiddenChannelIds(),
      overrides: getAllChannelOverrides(),
    },
    groupPreferences: collectGroupPreferences(),
  };
}

/** Write a backup snapshot to the given file path. */
export async function exportBackupToFile(
  filePath: string,
): Promise<{ ok: true; path: string; bytes: number } | { ok: false; error: string }> {
  try {
    const snapshot = buildBackup();
    const json = JSON.stringify(snapshot, null, 2);
    await fs.writeFile(filePath, json, 'utf-8');
    const stat = await fs.stat(filePath);
    log.info(`Backup exported to ${filePath} (${stat.size} bytes)`);
    return { ok: true, path: filePath, bytes: stat.size };
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    log.error('Backup export failed:', msg);
    return { ok: false, error: msg };
  }
}

/** Suggest a default filename like "yancotv-backup-2026-04-17.json". */
export function defaultBackupFilename(): string {
  const d = new Date();
  const yyyy = d.getFullYear();
  const mm = String(d.getMonth() + 1).padStart(2, '0');
  const dd = String(d.getDate()).padStart(2, '0');
  return `yancotv-backup-${yyyy}-${mm}-${dd}.json`;
}

// ---------------------------------------------------------------------------
// Import
// ---------------------------------------------------------------------------

export type ImportMode = 'merge' | 'replace';

export interface ImportStats {
  sourcesImported: number;
  favoritesImported: number;
  favoritesSkipped: number;
  historyImported: number;
  historySkipped: number;
  settingsImported: number;
  lockedImported: number;
  hiddenImported: number;
  overridesImported: number;
  groupPrefsImported: number;
}

export type ImportResult =
  | { ok: true; stats: ImportStats; warnings: string[] }
  | { ok: false; error: string };

/** Validate that a parsed JSON object looks like a BackupFile v1. */
function validateBackup(raw: unknown): raw is BackupFile {
  if (!raw || typeof raw !== 'object') return false;
  const b = raw as Partial<BackupFile>;
  if (typeof b.version !== 'number') return false;
  if (b.version !== BACKUP_VERSION) return false;
  if (!Array.isArray(b.sources)) return false;
  if (!Array.isArray(b.favorites)) return false;
  if (!Array.isArray(b.history)) return false;
  if (!b.settings || typeof b.settings !== 'object') return false;
  if (!b.parental || typeof b.parental !== 'object') return false;
  if (!Array.isArray(b.groupPreferences)) return false;
  return true;
}

function importSources(backup: BackupFile, mode: ImportMode): number {
  const db = getDb();
  if (mode === 'replace') {
    db.prepare('DELETE FROM sources').run();
  }

  // Use ON CONFLICT DO UPDATE rather than INSERT OR REPLACE — the latter
  // deletes-then-inserts on conflict, which cascades to content rows and
  // breaks favorites/history re-linking. This way the source row keeps its
  // identity and the content rows attached to it remain intact.
  const insert = db.prepare(
    `INSERT INTO sources (
      id, name, type, url, file_path, epg_url,
      username_encrypted, password_encrypted, mac_address_encrypted,
      priority, channel_count, last_sync_error, auto_sync_interval,
      last_synced, is_active, created_at, updated_at
    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
    ON CONFLICT(id) DO UPDATE SET
      name = excluded.name,
      type = excluded.type,
      url = excluded.url,
      file_path = excluded.file_path,
      epg_url = excluded.epg_url,
      username_encrypted = excluded.username_encrypted,
      password_encrypted = excluded.password_encrypted,
      mac_address_encrypted = excluded.mac_address_encrypted,
      priority = excluded.priority,
      channel_count = excluded.channel_count,
      last_sync_error = excluded.last_sync_error,
      auto_sync_interval = excluded.auto_sync_interval,
      last_synced = excluded.last_synced,
      is_active = excluded.is_active,
      updated_at = excluded.updated_at`,
  );

  let count = 0;
  for (const src of backup.sources) {
    let username: Buffer | null = null;
    let password: Buffer | null = null;
    let mac: Buffer | null = null;
    try {
      if (src.username) username = encryptCredential(src.username);
      if (src.password) password = encryptCredential(src.password);
      if (src.macAddress) mac = encryptCredential(src.macAddress);
    } catch (err) {
      log.warn(
        `Failed to re-encrypt credentials for source ${src.id} — OS keyring unavailable. Source will need credentials re-entered.`,
        err,
      );
    }

    // Stalker bypasses the original CHECK constraint the same way source-manager does.
    if (src.type === 'stalker') db.pragma('ignore_check_constraints = ON');
    try {
      insert.run(
        src.id,
        src.name,
        src.type,
        src.url ?? null,
        src.filePath ?? null,
        src.epgUrl ?? null,
        username,
        password,
        mac,
        src.priority,
        src.channelCount ?? 0,
        src.lastSyncError ?? null,
        src.autoSyncInterval ?? 0,
        src.lastSynced ?? null,
        src.isActive ? 1 : 0,
        src.createdAt,
        src.updatedAt,
      );
      count += 1;
    } finally {
      if (src.type === 'stalker') db.pragma('ignore_check_constraints = OFF');
    }
  }
  return count;
}

function importSettings(backup: BackupFile, mode: ImportMode): number {
  const db = getDb();
  if (mode === 'replace') {
    db.prepare('DELETE FROM settings').run();
  }
  const stmt = db.prepare('INSERT OR REPLACE INTO settings (key, value) VALUES (?, ?)');
  let count = 0;
  for (const [key, value] of Object.entries(backup.settings)) {
    stmt.run(key, value);
    count += 1;
  }
  return count;
}

function importParental(
  backup: BackupFile,
  mode: ImportMode,
): { locked: number; hidden: number; overrides: number } {
  const db = getDb();
  if (mode === 'replace') {
    db.prepare('DELETE FROM locked_channels').run();
    db.prepare('DELETE FROM hidden_channels').run();
    db.prepare('DELETE FROM channel_overrides').run();
  }
  const now = Date.now();
  const insertLocked = db.prepare(
    'INSERT OR IGNORE INTO locked_channels (content_id, locked_at) VALUES (?, ?)',
  );
  const insertHidden = db.prepare(
    'INSERT OR IGNORE INTO hidden_channels (content_id, hidden_at) VALUES (?, ?)',
  );
  const insertOverride = db.prepare(
    `INSERT OR REPLACE INTO channel_overrides
       (content_id, custom_name, custom_logo_url, custom_number, custom_group, updated_at)
     VALUES (?, ?, ?, ?, ?, ?)`,
  );

  let locked = 0;
  let hidden = 0;
  let overrides = 0;
  for (const id of backup.parental.lockedChannelIds) {
    const r = insertLocked.run(id, now);
    if (r.changes > 0) locked += 1;
  }
  for (const id of backup.parental.hiddenChannelIds) {
    const r = insertHidden.run(id, now);
    if (r.changes > 0) hidden += 1;
  }
  for (const [cid, ov] of Object.entries(backup.parental.overrides)) {
    insertOverride.run(
      cid,
      ov.customName ?? null,
      ov.customLogoUrl ?? null,
      ov.customNumber ?? null,
      ov.customGroup ?? null,
      now,
    );
    overrides += 1;
  }
  return { locked, hidden, overrides };
}

function importGroupPreferences(backup: BackupFile, mode: ImportMode): number {
  const db = getDb();
  if (mode === 'replace') {
    db.prepare('DELETE FROM group_preferences').run();
  }
  // Upsert by (content_type, group_key). Use ON CONFLICT via the unique index.
  const stmt = db.prepare(
    `INSERT INTO group_preferences
       (id, content_type, group_key, sort_order, is_hidden, is_pinned, custom_name, created_at)
     VALUES (?, ?, ?, ?, ?, ?, ?, ?)
     ON CONFLICT(content_type, group_key) DO UPDATE SET
       sort_order = excluded.sort_order,
       is_hidden  = excluded.is_hidden,
       is_pinned  = excluded.is_pinned,
       custom_name = excluded.custom_name`,
  );
  let count = 0;
  for (const g of backup.groupPreferences) {
    stmt.run(
      g.id,
      g.contentType,
      g.groupKey,
      g.sortOrder,
      g.isHidden ? 1 : 0,
      g.isPinned ? 1 : 0,
      g.customName,
      g.createdAt,
    );
    count += 1;
  }
  return count;
}

/**
 * Re-link a backup favorite/history ref to a current content row by
 * (source_id, stream_url). Content IDs regenerate on sync so the backup's
 * content_id doesn't work directly; the ref carries enough info to look up
 * the current ID.
 */
function lookupContentId(sourceId: string, streamUrl: string): string | null {
  const db = getDb();
  const row = db
    .prepare('SELECT id FROM content WHERE source_id = ? AND stream_url = ? LIMIT 1')
    .get(sourceId, streamUrl) as { id: string } | undefined;
  return row?.id ?? null;
}

function importFavorites(
  backup: BackupFile,
  mode: ImportMode,
): { imported: number; skipped: number } {
  const db = getDb();
  if (mode === 'replace') {
    db.prepare('DELETE FROM favorites').run();
  }
  const insert = db.prepare(
    'INSERT OR IGNORE INTO favorites (id, content_id, added_at) VALUES (?, ?, ?)',
  );
  let imported = 0;
  let skipped = 0;
  for (const f of backup.favorites) {
    const resolved = lookupContentId(f.ref.sourceId, f.ref.streamUrl);
    if (!resolved) {
      skipped += 1;
      continue;
    }
    const r = insert.run(uuid(), resolved, f.addedAt);
    if (r.changes > 0) imported += 1;
    else skipped += 1; // already a favorite (merge collision)
  }
  return { imported, skipped };
}

function importHistory(
  backup: BackupFile,
  mode: ImportMode,
): { imported: number; skipped: number } {
  const db = getDb();
  if (mode === 'replace') {
    db.prepare('DELETE FROM watch_history').run();
  }
  const insert = db.prepare(
    `INSERT INTO watch_history
       (id, content_id, episode_id, position_seconds, duration_seconds, watched_at)
     VALUES (?, ?, ?, ?, ?, ?)`,
  );
  let imported = 0;
  let skipped = 0;
  for (const h of backup.history) {
    const resolved = lookupContentId(h.ref.sourceId, h.ref.streamUrl);
    if (!resolved) {
      skipped += 1;
      continue;
    }
    insert.run(
      uuid(), // new id — cheap; avoids collisions with existing rows
      resolved,
      h.episodeId,
      h.positionSeconds,
      h.durationSeconds,
      h.watchedAt,
    );
    imported += 1;
  }
  return { imported, skipped };
}

/** Apply a backup snapshot to the current database inside a single transaction. */
export function importBackup(backup: BackupFile, mode: ImportMode): ImportResult {
  if (!validateBackup(backup)) {
    return { ok: false, error: 'Backup file is invalid or unsupported version' };
  }
  const db = getDb();
  const warnings: string[] = [];
  let stats: ImportStats = {
    sourcesImported: 0,
    favoritesImported: 0,
    favoritesSkipped: 0,
    historyImported: 0,
    historySkipped: 0,
    settingsImported: 0,
    lockedImported: 0,
    hiddenImported: 0,
    overridesImported: 0,
    groupPrefsImported: 0,
  };

  try {
    const tx = db.transaction(() => {
      stats.sourcesImported = importSources(backup, mode);
      stats.settingsImported = importSettings(backup, mode);
      const par = importParental(backup, mode);
      stats.lockedImported = par.locked;
      stats.hiddenImported = par.hidden;
      stats.overridesImported = par.overrides;
      stats.groupPrefsImported = importGroupPreferences(backup, mode);
      const fav = importFavorites(backup, mode);
      stats.favoritesImported = fav.imported;
      stats.favoritesSkipped = fav.skipped;
      const hist = importHistory(backup, mode);
      stats.historyImported = hist.imported;
      stats.historySkipped = hist.skipped;
    });
    tx();

    if (stats.favoritesSkipped > 0 || stats.historySkipped > 0) {
      warnings.push(
        `${stats.favoritesSkipped} favorites and ${stats.historySkipped} history rows were skipped — their source has not been synced yet. Re-sync your sources and import again to restore them.`,
      );
    }

    log.info(`Backup import complete (${mode}):`, stats);
    return { ok: true, stats, warnings };
  } catch (err) {
    const msg = err instanceof Error ? err.message : String(err);
    log.error('Backup import failed:', msg);
    return { ok: false, error: msg };
  }
}

/** Read a backup file from disk, parse it, and apply it. */
export async function importBackupFromFile(
  filePath: string,
  mode: ImportMode,
): Promise<ImportResult> {
  let raw: string;
  try {
    raw = await fs.readFile(filePath, 'utf-8');
  } catch (err) {
    return { ok: false, error: `Could not read ${filePath}: ${err instanceof Error ? err.message : String(err)}` };
  }

  let parsed: unknown;
  try {
    parsed = JSON.parse(raw);
  } catch {
    return { ok: false, error: 'Backup file is not valid JSON' };
  }

  if (!validateBackup(parsed)) {
    return { ok: false, error: 'Backup file is not a YancoTV backup or the version is unsupported' };
  }

  return importBackup(parsed, mode);
}
