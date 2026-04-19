import type {
  ContentItem,
  ContentType,
  HistoryEntry,
} from '@yancotv/core';
import { getDb } from './db';

/**
 * Mobile watch-history persistence.
 *
 * Mirrors desktop's src/main/services/history-store.ts: stateless async
 * functions over the watch_history table, joined to content when the UI
 * needs the full entry shape. A separate Zustand wrapper in
 * src/stores/history-store.ts holds the reactive "recently watched" list.
 */

interface HistoryRow {
  id: string;
  content_id: string;
  episode_id: string | null;
  position_seconds: number;
  duration_seconds: number | null;
  watched_at: number;
  source_id: string;
  type: string;
  title: string;
  clean_title: string | null;
  group_name: string | null;
  stream_url: string;
  logo_url: string | null;
  tvg_id: string | null;
  metadata_json: string | null;
  sort_order: number;
  created_at: number;
}

function rowToEntry(row: HistoryRow): HistoryEntry {
  const content: ContentItem = {
    id: row.content_id,
    sourceId: row.source_id,
    type: row.type as ContentType,
    title: row.title,
    cleanTitle: row.clean_title ?? undefined,
    groupName: row.group_name ?? undefined,
    streamUrl: row.stream_url,
    logoUrl: row.logo_url ?? undefined,
    tvgId: row.tvg_id ?? undefined,
    metadataJson: row.metadata_json ?? undefined,
    sortOrder: row.sort_order ?? 0,
    createdAt: row.created_at,
  };
  return {
    id: row.id,
    contentId: row.content_id,
    episodeId: row.episode_id ?? undefined,
    positionSeconds: row.position_seconds,
    durationSeconds: row.duration_seconds ?? undefined,
    watchedAt: row.watched_at,
    content,
  };
}

function makeHistoryId(): string {
  return `hist_${Date.now()}_${Math.random().toString(36).slice(2, 10)}`;
}

/** Most recent watch per (content, episode) pair, newest first. */
export async function getRecentlyWatched(limit = 20): Promise<HistoryEntry[]> {
  const db = getDb();
  // Per-(content, episode) latest row, ordered by recency. Subquery pins
  // each row to the most recent watched_at for its pair; GROUP BY folds
  // duplicate episodes if any sneak through.
  const res = await db.execute(
    `SELECT wh.id, wh.content_id, wh.episode_id, wh.position_seconds,
            wh.duration_seconds, wh.watched_at,
            c.source_id, c.type, c.title, c.clean_title, c.group_name,
            c.stream_url, c.logo_url, c.tvg_id, c.metadata_json,
            c.sort_order, c.created_at
       FROM watch_history wh
       JOIN content c ON c.id = wh.content_id
      WHERE wh.watched_at = (
        SELECT MAX(wh2.watched_at) FROM watch_history wh2
         WHERE wh2.content_id = wh.content_id
           AND (wh2.episode_id = wh.episode_id
                OR (wh2.episode_id IS NULL AND wh.episode_id IS NULL))
      )
      GROUP BY wh.content_id, wh.episode_id
      ORDER BY wh.watched_at DESC
      LIMIT ?`,
    [limit],
  );
  const rows = (res.rows ?? []) as unknown as HistoryRow[];
  return rows.map(rowToEntry);
}

export async function getLastPosition(
  contentId: string,
  episodeId?: string,
): Promise<{ positionSeconds: number; durationSeconds?: number } | null> {
  const db = getDb();

  const res = episodeId
    ? await db.execute(
        `SELECT position_seconds, duration_seconds FROM watch_history
         WHERE content_id = ? AND episode_id = ?
         ORDER BY watched_at DESC LIMIT 1`,
        [contentId, episodeId],
      )
    : await db.execute(
        `SELECT position_seconds, duration_seconds FROM watch_history
         WHERE content_id = ? AND episode_id IS NULL
         ORDER BY watched_at DESC LIMIT 1`,
        [contentId],
      );

  const row = (res.rows ?? [])[0] as unknown as
    | { position_seconds: number; duration_seconds: number | null }
    | undefined;
  if (!row) return null;
  return {
    positionSeconds: row.position_seconds,
    durationSeconds: row.duration_seconds ?? undefined,
  };
}

/** Latest position per episode, keyed by episode id. Drops zero-position rows. */
export async function getPositionsBatch(
  contentId: string,
  episodeIds: string[],
): Promise<Record<string, { positionSeconds: number; durationSeconds?: number }>> {
  if (episodeIds.length === 0) return {};
  const db = getDb();
  const placeholders = episodeIds.map(() => '?').join(',');
  const res = await db.execute(
    `SELECT episode_id, position_seconds, duration_seconds
       FROM watch_history
      WHERE content_id = ? AND episode_id IN (${placeholders})
        AND watched_at = (
          SELECT MAX(wh2.watched_at) FROM watch_history wh2
           WHERE wh2.content_id = watch_history.content_id
             AND wh2.episode_id = watch_history.episode_id
        )`,
    [contentId, ...episodeIds],
  );
  const rows = (res.rows ?? []) as unknown as Array<{
    episode_id: string;
    position_seconds: number;
    duration_seconds: number | null;
  }>;

  const result: Record<string, { positionSeconds: number; durationSeconds?: number }> = {};
  for (const row of rows) {
    if (row.position_seconds > 0) {
      result[row.episode_id] = {
        positionSeconds: row.position_seconds,
        durationSeconds: row.duration_seconds ?? undefined,
      };
    }
  }
  return result;
}

/** Insert a fresh row at position 0 and return the new history id. */
export async function recordWatch(
  contentId: string,
  episodeId?: string,
): Promise<string> {
  const db = getDb();
  const id = makeHistoryId();
  await db.execute(
    `INSERT INTO watch_history (id, content_id, episode_id, position_seconds, watched_at)
     VALUES (?, ?, ?, 0, ?)`,
    [id, contentId, episodeId ?? null, Date.now()],
  );
  return id;
}

/** Update position on an existing row. Duration is only written when provided. */
export async function updatePosition(
  historyId: string,
  positionSeconds: number,
  durationSeconds?: number,
): Promise<void> {
  const db = getDb();
  await db.execute(
    `UPDATE watch_history
        SET position_seconds = ?,
            duration_seconds = COALESCE(?, duration_seconds),
            watched_at = ?
      WHERE id = ?`,
    [positionSeconds, durationSeconds ?? null, Date.now(), historyId],
  );
}

export async function removeHistoryEntry(id: string): Promise<void> {
  const db = getDb();
  await db.execute('DELETE FROM watch_history WHERE id = ?', [id]);
}

export async function clearHistory(): Promise<void> {
  const db = getDb();
  await db.execute('DELETE FROM watch_history');
}
